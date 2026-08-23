package com.recall.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recall.capture.repository.CaptureRepository;
import com.recall.capture.service.entity.Capture;
import com.recall.llm.AiContextFactory;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.review.repository.ReviewRepository;
import com.recall.review.service.entity.ReviewItem;
import com.recall.store.service.SimilarMemoryFinder;
import java.util.ArrayList;
import java.util.List;
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
 * 조용한 실패 금지(불변 원칙)의 load-bearing 검증 — 파이프라인의 판정(S4) 단계를 강제로 실패시키면, 캡처가 조용히 사라지지 않고 status=FAILED +
 * failed_stage='judge' 로 durable 하게 드러나며, 부분 검토 항목이 새지 않는지 본다.
 *
 * <p>주입 지점: {@link SimilarMemoryFinder}를 던지도록 mock 한다(추출 전략을 직접 mock 하면 부팅 시 {@code
 * StrategyRegistry}가 {@code supports()}로 EnumMap 을 채우는데 mock 기본 반환이 null 이라 컨텍스트가 뜨지 못한다). finder 는
 * stage="judge" 구간에서 호출되므로 실패 단계는 'judge'.
 *
 * <p>트랜잭션 논거: onCaptureCreated 는 REQUIRES_NEW 인데 예외를 잡으므로 그 트랜잭션은 (아무 유의미한 쓰기 없이) 정상 커밋된다.
 * markFailed 는 자기 소유의 REQUIRES_NEW 로 FAILED 를 독립 커밋하므로, 파이프라인 트랜잭션과 무관하게 FAILED 가 남는다. 이 테스트가 실 DB에
 * 대고 그 사실을 증명한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaptureFailureExposureTest {

    private static final int POLL_TIMEOUT_MS = 5000;
    private static final int POLL_INTERVAL_MS = 100;

    @Autowired private MockMvc mockMvc;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private ReviewRepository reviewRepository;

    /** 판정 단계에서 던지게 만들어 파이프라인을 실패시킨다(stage=judge). */
    @MockitoBean private SimilarMemoryFinder similarMemoryFinder;

    /** 추출 단계의 LLM 은 stub(null→fallback)으로 — 무네트워크로 judge 단계까지 진행. */
    @MockitoBean private LlmClient llmClient;

    /**
     * 소유자(bootstrap 사용자) AI 컨텍스트를 항상 ready로 고정한다 — 이 테스트는 context 단계가 아니라 judge 단계 실패를 검증하므로, 테스트
     * 환경에 실제 chat/embedding 키가 없어도(로컬 dev 셸 밖) context 게이트에서 조기 실패하지 않게 한다.
     */
    @MockitoBean private AiContextFactory contextFactory;

    private final List<Long> createdCaptures = new ArrayList<>();

    @BeforeEach
    void arrange() {
        when(llmClient.complete(any(), any())).thenReturn(null);
        when(contextFactory.forUser(anyLong()))
                .thenReturn(
                        new UserAiContext(1L, llmClient, mock(EmbeddingClient.class), true, true));
        when(similarMemoryFinder.findSimilar(anyLong(), any(), any(), any()))
                .thenThrow(new RuntimeException("판정 단계 강제 실패(테스트)"));
    }

    @AfterEach
    void cleanup() {
        for (ReviewItem item : reviewRepository.findAll()) {
            if (createdCaptures.contains(item.getCaptureId())) {
                reviewRepository.delete(item);
            }
        }
        captureRepository.deleteAllById(createdCaptures);
        createdCaptures.clear();
    }

    @Test
    void pipelineFailureIsExposedAsFailedWithStageAndLeavesNoReviewItem() throws Exception {
        String body = "{ \"rawText\": \"실패 경로 캡처 본문\" }";
        String response =
                mockMvc.perform(
                                post("/api/captures")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long captureId = extractCaptureId(response);
        createdCaptures.add(captureId);

        String finalStatus = pollUntilTerminal(captureId);
        assertEquals("FAILED", finalStatus, "파이프라인 실패는 FAILED 로 드러나야 한다(조용한 실패 금지)");

        Capture reloaded = captureRepository.findById(captureId).orElseThrow();
        assertEquals("judge", reloaded.getFailedStage(), "실패 단계가 정확히 기록돼야 한다");

        boolean anyReview =
                reviewRepository.findAll().stream()
                        .anyMatch(i -> captureId.equals(i.getCaptureId()));
        assertFalse(anyReview, "실패 시 부분 검토 항목이 새면 안 된다");
    }

    private Long extractCaptureId(String response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response)
                // 공통 응답 형식 — 성공 본문은 data 안에 있다.
                .path("data")
                .path("captureId")
                .asLong();
    }

    private String pollUntilTerminal(Long captureId) throws Exception {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        String last = "PROCESSING";
        while (System.currentTimeMillis() < deadline) {
            last = captureRepository.findById(captureId).orElseThrow().getStatus();
            if (!"PROCESSING".equals(last)) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return last;
    }
}
