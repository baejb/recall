package com.recall.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.recall.capture.dto.CaptureStatusResponse;
import com.recall.common.CurrentUserProvider;
import com.recall.common.NotFoundException;
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

/** 🔴 캡처(원문 근거·처리상태)의 멀티유저 격리. 원본(마스킹된 원문)을 남의 id로 조회하거나, 남의 처리중/실패 캡처가 상태 목록에 보이면 안 된다. */
@Tag("release-gate")
@SpringBootTest
class CaptureIsolationTest {

    @Autowired private CaptureService captureService;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private CurrentUserProvider currentUser;

    private long userA;
    private long userB;
    private long captureB;
    private final List<Long> captureIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        userA = seedUser("capture-a");
        userB = seedUser("capture-b");
        seedProcessing(userA); // A 의 처리중 캡처
        captureB = seedProcessing(userB); // B 의 처리중 캡처
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

    private long seedProcessing(long userId) {
        // 리포지토리로 직접 저장(서비스 경유 X)해 비동기 파이프라인 없이 PROCESSING 상태로 둔다.
        Capture c = captureRepository.save(new Capture(userId, "chat", "마스킹된 원문", "[]"));
        captureIds.add(c.getId());
        return c.getId();
    }

    @AfterEach
    void cleanup() {
        captureRepository.deleteAllById(captureIds);
        captureIds.clear();
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        userIds.clear();
    }

    @Test
    @DisplayName("🔴 처리상태 목록은 소유자 캡처만 — 남의 처리중/실패가 안 보인다")
    void activeCapturesScopedToOwner() {
        when(currentUser.currentUserId()).thenReturn(userA);
        List<Long> idsA =
                captureService.activeCaptures().stream().map(CaptureStatusResponse::id).toList();
        assertTrue(!idsA.contains(captureB), "A 는 B 의 처리중 캡처를 보면 안 된다(교차유출)");
        assertEquals(1, idsA.size(), "A 의 처리상태 캡처는 1개");
    }

    @Test
    @DisplayName("🔴 원본(마스킹 원문) 조회: 남의 캡처 id 는 404")
    void rawOfOtherUsersCaptureIs404() {
        when(currentUser.currentUserId()).thenReturn(userA);
        assertThrows(NotFoundException.class, () -> captureService.getRaw(captureB));
    }
}
