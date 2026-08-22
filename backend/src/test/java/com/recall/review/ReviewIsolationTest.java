package com.recall.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.recall.capture.repository.CaptureRepository;
import com.recall.capture.service.entity.Capture;
import com.recall.common.config.CurrentUserProvider;
import com.recall.common.exception.NotFoundException;
import com.recall.common.type.MemoryType;
import com.recall.memory.type.Verdict;
import com.recall.review.controller.dto.ReviewItemResponse;
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
 * 🔴 검토 대기함(승인 게이트)의 멀티유저 격리. 검토 항목은 승인 전 추출 구조(마스킹된 원문 근거 포함)를 담으므로, 한 사용자가 남의 대기함을 조회하거나 남의 항목을
 * 승인/반려하면 안 된다. 소유자 해석은 {@link CurrentUserProvider} seam 을 mock 으로 전환해 검증한다.
 */
@Tag("release-gate")
@SpringBootTest
class ReviewIsolationTest {

    @Autowired private ReviewService reviewService;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private CurrentUserProvider currentUser;

    private long userA;
    private long userB;
    private long reviewB;
    private final List<Long> captureIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        userA = seedUser("review-a");
        userB = seedUser("review-b");
        seedPending(userA);
        reviewB = seedPending(userB);
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

    private long seedPending(long userId) {
        Capture capture = captureRepository.save(new Capture(userId, "chat", "원문", "[]"));
        captureIds.add(capture.getId());
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
        return item.getId();
    }

    @AfterEach
    void cleanup() {
        // review_queue 는 capture FK 로 함께 지워진다(TRUNCATE 아닌 delete 경로라 명시 삭제).
        for (ReviewItem item : reviewRepository.findAll()) {
            if (captureIds.contains(item.getCaptureId())) {
                reviewRepository.delete(item);
            }
        }
        captureRepository.deleteAllById(captureIds);
        captureIds.clear();
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        userIds.clear();
    }

    @Test
    @DisplayName("🔴 대기함 목록·카운트는 소유자 기준 — 남의 검토 항목이 안 보인다")
    void pendingListAndCountScopedToOwner() {
        when(currentUser.currentUserId()).thenReturn(userA);
        List<ReviewItemResponse> aPending = reviewService.listPending();
        assertEquals(1, aPending.size(), "A 대기 항목은 1개");
        assertEquals(1, reviewService.countPending(), "A 대기 카운트는 1");
        assertTrue(
                aPending.stream().noneMatch(r -> r.id() == reviewB),
                "A 는 B 의 검토 항목을 보면 안 된다(교차유출)");
    }

    @Test
    @DisplayName("🔴 남의 검토 항목 승인/반려는 404 — 존재를 노출하지 않는다")
    void approveRejectOnOtherUsersItemIs404() {
        when(currentUser.currentUserId()).thenReturn(userA);
        assertThrows(NotFoundException.class, () -> reviewService.approve(reviewB));
        assertThrows(NotFoundException.class, () -> reviewService.reject(reviewB));
    }
}
