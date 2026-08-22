package com.recall.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.recall.capture.repository.CaptureRepository;
import com.recall.capture.service.entity.Capture;
import com.recall.common.config.CurrentUserProvider;
import com.recall.common.exception.AiNotConfiguredException;
import com.recall.common.type.MemoryType;
import com.recall.llm.AiContextFactory;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.Verdict;
import com.recall.review.repository.ReviewRepository;
import com.recall.review.service.ReviewService;
import com.recall.review.service.entity.ReviewItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 🔴 승인 인덱싱은 소유자(현재 사용자)의 embedding 컨텍스트를 쓴다(Task 8: 인덱싱이 주입된 싱글턴 대신 {@link
 * AiContextFactory#forUser(long)}으로 얻은 embedding을 쓴다). 소유자가 embedding을 설정하지 않았으면 인덱싱 단계에서 {@link
 * AiNotConfiguredException}(→409)이 승인 트랜잭션 전체를 롤백시켜, memory 미저장·검토 항목 미확정인 깨끗한 실패로 남아야 한다(부분 상태 없음
 * — CLAUDE.md 조용한 실패/절단 금지와 승인 게이트 원칙의 교차점).
 */
@Tag("release-gate")
@SpringBootTest
class ReviewApproveContextTest {

    @Autowired private ReviewService reviewService;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private CurrentUserProvider currentUser;
    @MockitoBean private AiContextFactory contextFactory;

    private long ownerId;
    private Long reviewId;
    private Long captureId;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        ownerId = seedUser("approve-ctx-owner");
        when(currentUser.currentUserId()).thenReturn(ownerId);

        Capture capture = captureRepository.save(new Capture(ownerId, "chat", "원문", "[]"));
        captureId = capture.getId();
        ReviewItem item =
                reviewRepository.save(
                        new ReviewItem(
                                capture.getId(),
                                capture.getUserId(),
                                MemoryType.KNOWLEDGE,
                                Verdict.NEW,
                                null,
                                "판정 근거",
                                "{\"title\":\"제안\"}"));
        reviewId = item.getId();

        // 소유자의 embedding 은 미설정(embeddingReady=false) — 이 테스트는 embedding 게이트만 검증하므로 chat 도
        // 함께 false 로 둔다(approve 경로는 chat 을 쓰지 않으므로 무관).
        when(contextFactory.forUser(ownerId))
                .thenReturn(
                        new UserAiContext(
                                ownerId,
                                mock(LlmClient.class),
                                mock(EmbeddingClient.class),
                                false,
                                false));
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

    @AfterEach
    void cleanup() {
        reviewRepository.findById(reviewId).ifPresent(reviewRepository::delete);
        captureRepository.deleteById(captureId);
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        userIds.clear();
    }

    @Test
    @DisplayName("승인 인덱싱은 현재 사용자 임베딩 컨텍스트 사용(미설정이면 409)")
    void approveUsesOwnerEmbedding() {
        assertThrows(AiNotConfiguredException.class, () -> reviewService.approve(reviewId));

        // 트랜잭션 전체 롤백 — 검토 항목은 여전히 pending, memory 는 생기지 않는다(부분 상태 없음).
        ReviewItem reloaded = reviewRepository.findById(reviewId).orElseThrow();
        assertEquals(
                "pending",
                reloaded.getStatus(),
                "임베딩 미설정으로 인덱싱이 막히면 검토 항목은 approved 로 전이되면 안 된다(전체 롤백)");

        Integer memoryCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM memory WHERE capture_id = ?",
                        Integer.class,
                        captureId);
        assertEquals(0, memoryCount, "임베딩 미설정으로 승인이 막히면 memory 가 저장되면 안 된다(부분 상태 없음)");
    }
}
