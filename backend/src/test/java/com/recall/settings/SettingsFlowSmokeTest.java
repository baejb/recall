package com.recall.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.StubEmbeddingClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 통합 스모크(부팅→설정 조회→임베딩 provider 변경→비동기 재색인→READY 수렴) — 외부 네트워크 호출 없이 stub 임베딩만 사용한다.
 *
 * <p>P1-b(유효 키 없는 임베딩 provider/모델 변경은 기존 벡터 보호를 위해 거부)에 맞춰, env 임베딩
 * 키(recall.llm.embedding.api-key)를 비어 있지 않게 주입해 유효 키 요건을 만족시킨다(요청 바디의 apiKey 는 여전히 null — cipher
 * 불필요). 실 provider 로 네트워크가 나가지 않도록 {@link EmbeddingClientFactory} 빈을 {@link MockitoBean}으로 대체해
 * 프로브·재색인 모두 {@link StubEmbeddingClient}(0벡터)를 쓰게 한다 — 이 테스트가 검증하는 건 provider 선택이 아니라
 * 커밋→AFTER_COMMIT 이벤트→비동기 재색인→READY 수렴의 배선이다.
 *
 * <p>일부러 {@code @Transactional}을 붙이지 않는다 — 재색인은 {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Async}로 발화하므로 PUT 트랜잭션이 실제로 커밋돼야 이벤트가 발행된다. 테스트가 끝나면 model_setting(id=1) 행을 원상복구한다(실 DB를
 * 공유하는 다른 테스트에 잔여 영향 방지).
 */
@SpringBootTest(properties = "recall.llm.embedding.api-key=sk-smoke-env")
@AutoConfigureMockMvc
class SettingsFlowSmokeTest {

    private static final int POLL_TIMEOUT_MS = 5000;
    private static final int POLL_INTERVAL_MS = 100;

    @Autowired private MockMvc mockMvc;
    @Autowired private ModelSettingRepository repository;

    /**
     * 프로브(SettingsService)와 재색인(SettingsBackedEmbeddingClient)이 공유하는 팩토리 — stub 으로 대체해 무네트워크 보장.
     */
    @MockitoBean private EmbeddingClientFactory embeddingClientFactory;

    private String originalEmbeddingProvider;
    private String originalEmbeddingModel;
    private String originalEmbeddingStatus;

    @BeforeEach
    void captureOriginalRow() {
        when(embeddingClientFactory.forSettings(any())).thenReturn(new StubEmbeddingClient());
        ModelSetting s = repository.findById(1L).orElseThrow();
        originalEmbeddingProvider = s.getEmbeddingProvider();
        originalEmbeddingModel = s.getEmbeddingModel();
        originalEmbeddingStatus = s.getEmbeddingStatus();
    }

    @AfterEach
    void restoreOriginalRow() {
        ModelSetting s = repository.findById(1L).orElseThrow();
        s.setEmbeddingProvider(originalEmbeddingProvider);
        s.setEmbeddingModel(originalEmbeddingModel);
        s.setEmbeddingStatus(originalEmbeddingStatus);
        repository.save(s);
    }

    @Test
    void bootReadSettingsChangeEmbeddingProviderAndReindexConvergesToReady() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // 1) 부팅 후 기본 설정 조회
        String getBody =
                mockMvc.perform(get("/api/settings/models"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.chat.provider").value("anthropic"))
                        .andExpect(jsonPath("$.embedding.provider").value("voyage"))
                        .andExpect(jsonPath("$.embedding.status").value("READY"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode getJson = mapper.readTree(getBody);
        assertTrue(getJson.has("embedding"), "embedding 슬롯이 응답에 있어야 한다");

        // 2) 역할별 provider 카탈로그 — capability 비대칭 확인
        mockMvc.perform(get("/api/settings/models/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingModels.anthropic").doesNotExist())
                .andExpect(jsonPath("$.chatModels.voyage").doesNotExist())
                .andExpect(jsonPath("$.chatModels.anthropic").exists())
                .andExpect(jsonPath("$.embeddingModels.voyage").exists());

        // 3) 임베딩 provider 변경 → 재색인 트리거. P1-b 로 유효 키가 필수인데, env 키를 주입해 요건을 만족하므로
        //    요청 바디의 apiKey 는 null 로 둔다(팩토리는 MockitoBean 이라 실제 provider 로 나가지 않는다).
        String requestBody =
                """
                { "embedding": {"provider": "openai", "model": null, "apiKey": null} }
                """;
        mockMvc.perform(
                        put("/api/settings/models")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.provider").value("openai"));

        // 4) 비동기 재색인이 완료되어 상태가 READY 로 수렴하는지 폴링 확인.
        // READY -> REINDEXING -> READY 로 전이하지만, stub + 빈/소량 active memory 셋에서는
        // 재색인이 거의 즉시 끝나 REINDEXING 을 관측하지 못할 수도 있다 — 그 경우도 정상이며,
        // 이 테스트가 확인하는 것은 "최종적으로 READY 로 끝나고 예외/5xx 가 없었다"는 사실이다.
        String finalStatus = pollUntilReady();
        assertEquals("READY", finalStatus, "재색인 후 embedding.status 는 READY 로 수렴해야 한다");
    }

    private String pollUntilReady() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        String lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            String body =
                    mockMvc.perform(get("/api/settings/models"))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            lastStatus = mapper.readTree(body).path("embedding").path("status").asText();
            if ("READY".equals(lastStatus)) {
                return lastStatus;
            }
            assertTrue(
                    "READY".equals(lastStatus) || "REINDEXING".equals(lastStatus),
                    "재색인 중 예상치 못한 상태로 전이됨(예: FAILED): " + lastStatus);
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return lastStatus;
    }
}
