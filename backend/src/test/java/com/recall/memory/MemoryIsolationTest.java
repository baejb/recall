package com.recall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.recall.capture.Capture;
import com.recall.capture.CaptureRepository;
import com.recall.common.CurrentUserProvider;
import com.recall.common.MemoryType;
import com.recall.common.NotFoundException;
import com.recall.memory.dto.MemoryPageResponse;
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
 * 🔴 멀티유저 교차유출 금지(불변 원칙)의 릴리스 차단 게이트. 한 사용자는 다른 사용자의 기억을 목록·카운트·상세·상태전이 어디서도 접근할 수 없어야 한다.
 *
 * <p>소유자 해석은 {@link CurrentUserProvider} seam 을 mock 으로 바꿔 사용자를 전환하며 검증한다. 부트스트랩 사용자(1)는 다른
 * {@code @SpringBootTest} 들이 기본 소유자로 함께 쓰므로, 이 게이트는 잔여 데이터에 흔들리지 않도록 두 사용자를 모두 새로 시드해 쓴다.
 */
@Tag("release-gate")
@SpringBootTest
class MemoryIsolationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private MemoryRepository memoryRepository;
    @Autowired private JdbcTemplate jdbc;

    /** 소유자 해석 seam — 테스트가 사용자 A/B 를 전환한다. */
    @MockitoBean private CurrentUserProvider currentUser;

    private long userA;
    private long userB;
    private Long memoryB;
    private final List<Long> captureIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        // 다른 테스트와 격리되도록 전용 사용자 2명을 새로 시드한다(id 는 IDENTITY 가 부여, RETURNING 으로 회수).
        userA = seedUser("user-a");
        userB = seedUser("user-b");

        persistMemory(userA, "A의 기억");
        memoryB = persistMemory(userB, "B의 기억");
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

    private Long persistMemory(long userId, String title) {
        Capture capture = captureRepository.save(new Capture(userId, "chat", "마스킹된 원문", "[]"));
        captureIds.add(capture.getId());
        Memory memory =
                memoryRepository.save(
                        new Memory(
                                capture,
                                MemoryType.KNOWLEDGE,
                                title,
                                "{\"title\":\"" + title + "\"}"));
        return memory.getId();
    }

    @AfterEach
    void cleanup() {
        // capture 삭제 시 memory 는 FK ON DELETE CASCADE 로 함께 지워진다. 그 뒤 테스트 사용자 정리.
        captureRepository.deleteAllById(captureIds);
        captureIds.clear();
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        userIds.clear();
    }

    @Test
    @DisplayName("🔴 목록은 소유자 기억만 반환한다 — 남의 기억이 섞이지 않는다")
    void listReturnsOnlyOwnMemories() {
        when(currentUser.currentUserId()).thenReturn(userA);
        List<String> titlesA = titles();
        assertTrue(titlesA.contains("A의 기억"), "A는 자기 기억을 본다");
        assertTrue(!titlesA.contains("B의 기억"), "A는 B의 기억을 보면 안 된다(교차유출)");

        when(currentUser.currentUserId()).thenReturn(userB);
        List<String> titlesB = titles();
        assertTrue(titlesB.contains("B의 기억"), "B는 자기 기억을 본다");
        assertTrue(!titlesB.contains("A의 기억"), "B는 A의 기억을 보면 안 된다(교차유출)");
    }

    @Test
    @DisplayName("🔴 카운트도 소유자 기준 — 남의 기억은 세지 않는다")
    void countsAreScopedToOwner() {
        when(currentUser.currentUserId()).thenReturn(userA);
        // 전용 사용자 A 는 이 테스트가 만든 기억 1개뿐이므로 total 은 정확히 1(남의 기억이 섞이면 커진다).
        MemoryPageResponse page = memoryService.list(null, null, null, 50);
        assertEquals(1, page.items().size(), "A 의 활성 기억은 1개여야 한다");
        assertEquals(1, page.counts().total(), "counts.total 에 남의 기억이 세어지면 안 된다");
    }

    @Test
    @DisplayName("🔴 상세: 남의 기억 id 는 404(존재를 노출하지 않는다)")
    void detailOfOtherUsersMemoryIs404() {
        when(currentUser.currentUserId()).thenReturn(userA);
        assertThrows(NotFoundException.class, () -> memoryService.getDetail(memoryB));
    }

    @Test
    @DisplayName("🔴 상태전이: 남의 기억은 숨김/폐기할 수 없다(404)")
    void statusChangeOnOtherUsersMemoryIs404() {
        when(currentUser.currentUserId()).thenReturn(userA);
        assertThrows(
                NotFoundException.class, () -> memoryService.updateStatus(memoryB, "archived"));
    }

    private List<String> titles() {
        return memoryService.list(null, null, null, 50).items().stream()
                .map(m -> m.title())
                .toList();
    }
}
