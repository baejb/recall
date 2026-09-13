package com.recall.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.recall.capture.repository.CaptureRepository;
import com.recall.capture.service.entity.Capture;
import com.recall.common.config.CurrentUserProvider;
import com.recall.common.type.MemoryType;
import com.recall.memory.type.Verdict;
import com.recall.review.controller.dto.ReviewItemResponse;
import com.recall.review.repository.ReviewRepository;
import com.recall.review.service.ReviewService;
import com.recall.review.service.entity.ReviewItem;
import com.recall.review.service.entity.ReviewStatus;
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
 * 처리된(승인·반려) 검토 항목을 다시 볼 수 있는지 고정한다.
 *
 * <p><b>왜 필요한가</b> — 반려는 삭제가 아니라 상태 전이다(불변 원칙 3). 원문({@code capture})도 그대로 남는다. 그런데 조회 경로가 {@code
 * pending} 하나뿐이라 반려한 항목은 DB 에만 있고 화면에서 닿을 방법이 없었다: 잘못 반려하면 복구는커녕 확인도 못 하고, 같은 원문을 또 붙여넣게 된다. 기억 쪽은
 * {@code archived}·{@code incorrect} 를 상태 탭으로 볼 수 있는데 검토 쪽만 그러지 못했다 — 같은 원칙을 두 모듈이 다르게 대접하고 있었다.
 *
 * <p>소유자 스코프도 함께 건다. 처리된 항목에도 마스킹된 원문 근거와 추출 구조가 들어 있어, 목록이 하나 늘어난 만큼 교차유출 표면도 하나 는다.
 */
@Tag("release-gate")
@SpringBootTest
class ReviewHistoryTest {

    @Autowired private ReviewService reviewService;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private CurrentUserProvider currentUser;

    private long userA;
    private long userB;
    private long pendingA;
    private long rejectedA;
    private long rejectedB;
    private final List<Long> captureIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        userA = seedUser("history-a");
        userB = seedUser("history-b");
        pendingA = seedItem(userA);
        rejectedA = seedItem(userA);
        rejectedB = seedItem(userB);

        when(currentUser.currentUserId()).thenReturn(userA);
        reviewService.reject(rejectedA);
        when(currentUser.currentUserId()).thenReturn(userB);
        reviewService.reject(rejectedB);
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

    private long seedItem(long userId) {
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
    @DisplayName("반려한 항목은 처리됨 목록에서 다시 볼 수 있다 — 원문·판정 근거와 함께")
    void rejectedItemIsVisibleInHistory() {
        when(currentUser.currentUserId()).thenReturn(userA);

        List<ReviewItemResponse> processed = reviewService.listProcessed();

        ReviewItemResponse rejected =
                processed.stream().filter(r -> r.id() == rejectedA).findFirst().orElse(null);
        assertNotNull(rejected, "반려한 항목이 처리됨 목록에 없다 — 화면에서 닿을 방법이 사라진다");
        assertEquals(ReviewStatus.REJECTED, rejected.status());
        assertNotNull(rejected.resolvedAt(), "언제 처리했는지가 없으면 목록을 시간순으로 읽을 수 없다");
        assertEquals("판정 근거", rejected.judgeReason(), "판정 근거는 반려 후에도 남아야 한다");
    }

    @Test
    @DisplayName("처리됨 목록에 승인 대기 항목이 섞이지 않는다 — 두 목록의 경계")
    void historyExcludesPending() {
        when(currentUser.currentUserId()).thenReturn(userA);

        assertTrue(
                reviewService.listProcessed().stream().noneMatch(r -> r.id() == pendingA),
                "대기 항목이 처리됨 목록에 섞였다");
        assertTrue(
                reviewService.listPending().stream().noneMatch(r -> r.id() == rejectedA),
                "반려 항목이 대기 목록에 남았다 — 이미 처리한 것을 또 처리하게 된다");
    }

    @Test
    @DisplayName("🔴 처리됨 목록도 소유자 기준 — 남이 반려한 항목이 안 보인다")
    void historyScopedToOwner() {
        when(currentUser.currentUserId()).thenReturn(userA);

        assertTrue(
                reviewService.listProcessed().stream().noneMatch(r -> r.id() == rejectedB),
                "A 가 B 의 처리된 검토 항목을 봤다(교차유출)");
    }
}
