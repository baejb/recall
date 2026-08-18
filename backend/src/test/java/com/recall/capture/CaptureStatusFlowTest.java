package com.recall.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.StubEmbeddingClient;
import com.recall.review.ReviewItem;
import com.recall.review.ReviewRepository;
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
 * 성공 경로 + 상태 노출 엔드포인트 통합. 일부러 {@code @Transactional}을 붙이지 않는다 — 저장 파이프라인이
 * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async}라 POST 트랜잭션이 실제로 커밋돼야 발화한다. 외부
 * 네트워크가 나가지 않도록 LLM/임베딩 포트를 stub 으로 대체(추출·판정은 fallback 으로 흐름을 끝까지 이어감). 공유 테이블을 쓰므로 만든 행은 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaptureStatusFlowTest {

    private static final int POLL_TIMEOUT_MS = 5000;
    private static final int POLL_INTERVAL_MS = 100;

    @Autowired private MockMvc mockMvc;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private ReviewRepository reviewRepository;

    /** 무네트워크 결정성: 추출/판정의 LLM 호출을 stub 으로. null 반환 → 파싱 실패 → fallback 카드로 흐름 완료. */
    @MockitoBean private LlmClient llmClient;

    /** 유사후보 탐색의 임베딩도 stub(0벡터)로 — 무네트워크. */
    @MockitoBean private EmbeddingClient embeddingClient;

    private final List<Long> createdCaptures = new ArrayList<>();

    @BeforeEach
    void stubPorts() {
        when(llmClient.complete(any(), any())).thenReturn(null);
        StubEmbeddingClient stub = new StubEmbeddingClient();
        when(embeddingClient.dimension()).thenReturn(stub.dimension());
        when(embeddingClient.embedDocument(any())).thenReturn(stub.embedDocument(""));
        when(embeddingClient.embedQuery(any())).thenReturn(stub.embedQuery(""));
    }

    @AfterEach
    void cleanup() {
        for (ReviewItem item : reviewRepository.findAll()) {
            if (item.getCapture() != null && createdCaptures.contains(item.getCapture().getId())) {
                reviewRepository.delete(item);
            }
        }
        captureRepository.deleteAllById(createdCaptures);
        createdCaptures.clear();
    }

    @Test
    void successFlowEndsDoneAndCreatesReviewItem() throws Exception {
        String body = "{ \"rawText\": \"성공 경로 캡처 본문\" }";
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
        assertEquals("DONE", finalStatus, "성공 경로는 DONE 으로 끝나야 한다");

        boolean reviewExists =
                reviewRepository.findAll().stream()
                        .anyMatch(
                                i ->
                                        i.getCapture() != null
                                                && captureId.equals(i.getCapture().getId()));
        assertTrue(reviewExists, "성공 경로는 검토 대기함 항목을 만든다");
    }

    @Test
    void activeEndpointReturnsProcessingAndFailedExcludesDoneAndHasNoRawText() throws Exception {
        Capture processing = seedRawTextCapture("PROCESSING", "PROCESSING_원문_비밀");
        Capture failed = seedRawTextCapture("FAILED", "FAILED_원문_비밀");
        Capture done = seedRawTextCapture("DONE", "DONE_원문_비밀");
        captureRepository.markFailed(failed.getId(), "judge");

        String json =
                mockMvc.perform(get("/api/captures/active"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // id 는 문자열 부분일치가 아니라 파싱한 id 필드로 정확히 비교한다(타임스탬프 숫자와의 우연 일치 방지).
        com.fasterxml.jackson.databind.JsonNode arr =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        java.util.Set<Long> ids = new java.util.HashSet<>();
        arr.forEach(n -> ids.add(n.path("id").asLong()));
        assertTrue(ids.contains(processing.getId()), "PROCESSING 포함");
        assertTrue(ids.contains(failed.getId()), "FAILED 포함");
        assertFalse(ids.contains(done.getId()), "DONE 은 제외");
        // 원문(raw_text)이 응답에 절대 실리지 않아야 한다.
        assertFalse(json.contains("_원문_비밀"), "상태 응답에 원문이 실리면 안 된다");
        assertFalse(json.contains("rawText"), "raw text 필드가 없어야 한다");
        assertFalse(json.contains("raw_text"), "raw_text 필드가 없어야 한다");
    }

    private Capture seedRawTextCapture(String status, String rawText) {
        Capture c = captureRepository.save(new Capture(1L, "chat", rawText, "[]"));
        createdCaptures.add(c.getId());
        if (!"PROCESSING".equals(status)) {
            c.setStatus(status);
            captureRepository.save(c);
        }
        return c;
    }

    private Long extractCaptureId(String response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response)
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
