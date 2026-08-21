package com.recall.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.recall.capture.Capture;
import com.recall.capture.CaptureCreatedEvent;
import com.recall.capture.CaptureRepository;
import com.recall.review.ReviewRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 🔴 저장 경로의 새 첫 단계(context) — capture 소유자의 AI(chat/embedding)가 미설정이면, 이후
 * 단계(classify/extract/judge)로 새지 않고 즉시 FAILED + failed_stage='context' 로 durable 하게 드러나야 한다(조용한 실패
 * 금지 + 승인 게이트: 미설정 상태로 만든 부분 결과가 검토 대기함에 오르면 안 된다).
 *
 * <p>owner 는 bootstrap 이 아닌 새 사용자(model_setting 행 없음 — {@code AiContextFactory#forUser} 계약상 미설정)라
 * 실제 chat/embedding 키 유무와 무관하게 결정적으로 재현된다({@link com.recall.llm.AiContextFactoryTest}와 동일한 시드 방식).
 *
 * <p>{@link StorePipeline#onCaptureCreated}는 {@code @Async}라 이벤트 발행 대신 빈을 직접 호출해도 비동기로 실행된다(Spring
 * AOP 프록시가 가로챈다) — capture 소유자를 bootstrap 이 아닌 사용자로 만들려면 HTTP 왕복(항상 bootstrap 로 인증됨) 대신 이 방식이 필요하다.
 */
@Tag("release-gate")
@SpringBootTest
class StoreContextIsolationTest {

    private static final int POLL_TIMEOUT_MS = 5000;
    private static final int POLL_INTERVAL_MS = 100;

    @Autowired private StorePipeline storePipeline;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> captureIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        captureRepository.deleteAllById(captureIds);
        captureIds.clear();
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        userIds.clear();
    }

    @Test
    @DisplayName("🔴 소유자 AI 미설정이면 저장이 FAILED + failed_stage=context, 검토 항목 안 새어나감")
    void unconfiguredOwnerFailsAtContext() throws InterruptedException {
        long unconfiguredOwner = seedUser("store-ctx-unconfigured"); // model_setting 행 없음 → 미설정

        Capture capture =
                captureRepository.save(
                        new Capture(unconfiguredOwner, "chat", "컨텍스트 게이트 테스트 원문", "[]"));
        captureIds.add(capture.getId());

        storePipeline.onCaptureCreated(
                new CaptureCreatedEvent(capture.getId(), capture.getRawText()));

        String finalStatus = pollUntilTerminal(capture.getId());
        assertEquals("FAILED", finalStatus, "소유자 AI 미설정은 FAILED 로 드러나야 한다(조용한 실패 금지)");

        Capture reloaded = captureRepository.findById(capture.getId()).orElseThrow();
        assertEquals(
                "context",
                reloaded.getFailedStage(),
                "미설정 실패는 classify/extract/judge 가 아니라 context 단계로 귀속돼야 한다");

        boolean anyReview =
                reviewRepository.findAll().stream()
                        .anyMatch(
                                i ->
                                        i.getCapture() != null
                                                && capture.getId().equals(i.getCapture().getId()));
        assertFalse(anyReview, "context 게이트 실패 시 부분 검토 항목이 새면 안 된다(승인 게이트)");
    }

    private long seedUser(String subject) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO app_user (provider, subject, display_name) "
                                + "VALUES ('test', ?, ?) RETURNING id",
                        Long.class,
                        subject,
                        subject);
        userIds.add(id);
        return id;
    }

    private String pollUntilTerminal(Long captureId) throws InterruptedException {
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
