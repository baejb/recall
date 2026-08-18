package com.recall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.capture.Capture;
import com.recall.capture.CaptureRepository;
import com.recall.common.BadRequestException;
import com.recall.common.MemoryType;
import com.recall.common.NotFoundException;
import com.recall.memory.dto.MemoryDetailResponse;
import com.recall.memory.dto.MemoryPageResponse;
import com.recall.memory.dto.MemoryResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 기억 목록 키셋 페이지네이션의 실 DB 계약. 공유 테이블을 쓰므로 고유 토큰(TOK)을 제목에 넣고 q=TOK 로 이 테스트가 만든 행만 격리해 검증한다(선행 데이터에
 * 영향받지 않게). 만든 행은 정리한다.
 */
@SpringBootTest
class MemoryListPaginationTest {

    private static final String TOK = "ZZKEYSETPGT"; // 다른 데이터와 겹치지 않는 고유 검색 토큰

    @Autowired MemoryService memoryService;
    @Autowired MemoryRepository memoryRepository;
    @Autowired CaptureRepository captureRepository;

    private final List<Long> memIds = new ArrayList<>();
    private Long captureId;

    /** TOK 1..5 를 순서대로 저장(@CreationTimestamp·IDENTITY 로 뒤 저장일수록 최신·큰 id). kn: 1,3,5 / ts: 2,4. */
    @BeforeEach
    void seed() {
        Capture c = captureRepository.save(new Capture(1L, "chat", "마스킹된 원문", "[]"));
        captureId = c.getId();
        for (int i = 1; i <= 5; i++) {
            MemoryType type = (i % 2 == 1) ? MemoryType.KNOWLEDGE : MemoryType.TROUBLESHOOTING;
            Memory m = new Memory(c, type, TOK + " " + i, "{}");
            memoryRepository.saveAndFlush(m);
            memIds.add(m.getId());
        }
    }

    @AfterEach
    void cleanup() {
        memoryRepository.deleteAllById(memIds);
        memIds.clear();
        if (captureId != null) {
            captureRepository.deleteById(captureId);
            captureId = null;
        }
    }

    private static List<String> titles(MemoryPageResponse page) {
        return page.items().stream().map(MemoryResponse::title).toList();
    }

    private static List<Long> ids(MemoryPageResponse page) {
        return page.items().stream().map(MemoryResponse::id).toList();
    }

    @Test
    @DisplayName("첫 페이지는 최신순 limit개 + nextCursor + counts, 이어지는 페이지는 겹침 없이 이어지고 counts=null")
    void keysetWalksAllPagesWithoutOverlap() {
        // 페이지1 (최신순: 5,4)
        MemoryPageResponse p1 = memoryService.list(TOK, null, null, 2);
        assertEquals(List.of(TOK + " 5", TOK + " 4"), titles(p1));
        assertNotNull(p1.nextCursor());
        assertNotNull(p1.counts());
        assertEquals(5, p1.counts().total());

        // 페이지2 (3,2) — 커서 있으면 counts=null
        MemoryPageResponse p2 = memoryService.list(TOK, null, p1.nextCursor(), 2);
        assertEquals(List.of(TOK + " 3", TOK + " 2"), titles(p2));
        assertNotNull(p2.nextCursor());
        assertNull(p2.counts());

        // 페이지3 (1) — 마지막, nextCursor=null
        MemoryPageResponse p3 = memoryService.list(TOK, null, p2.nextCursor(), 2);
        assertEquals(List.of(TOK + " 1"), titles(p3));
        assertNull(p3.nextCursor());

        // 5건 정확히 한 번씩
        List<String> all = new ArrayList<>();
        all.addAll(titles(p1));
        all.addAll(titles(p2));
        all.addAll(titles(p3));
        assertEquals(5, all.size());
        assertEquals(5, all.stream().distinct().count());
    }

    @Test
    @DisplayName("유형 필터(kn|ts)와 counts 가 유형별로 맞다")
    void filtersByTypeAndCounts() {
        MemoryPageResponse all = memoryService.list(TOK, null, null, 20);
        assertEquals(5, all.counts().total());
        assertEquals(3, all.counts().kn());
        assertEquals(2, all.counts().ts());

        MemoryPageResponse kn = memoryService.list(TOK, "kn", null, 20);
        assertEquals(List.of(TOK + " 5", TOK + " 3", TOK + " 1"), titles(kn));
        assertNull(kn.nextCursor());

        MemoryPageResponse ts = memoryService.list(TOK, "ts", null, 20);
        assertEquals(List.of(TOK + " 4", TOK + " 2"), titles(ts));
    }

    @Test
    @DisplayName("제목 검색은 부분일치·대소문자 무시")
    void searchesTitleCaseInsensitive() {
        MemoryPageResponse hit = memoryService.list(TOK.toLowerCase() + " 3", null, null, 20);
        assertEquals(List.of(TOK + " 3"), titles(hit));

        MemoryPageResponse miss = memoryService.list(TOK + " 없는제목", null, null, 20);
        assertTrue(miss.items().isEmpty());
        assertEquals(0, miss.counts().total());
    }

    @Test
    @DisplayName("검색어 없이(q=null)도 전체 목록을 반환한다 — null 파라미터 타입 추론 실패 회귀")
    void emptyQueryReturnsAllWithoutError() {
        // q=null 첫 페이지: 예외 없이 결과를 준다(선행 데이터 때문에 절대 개수는 단정하지 않되,
        // counts.total 은 우리가 넣은 5건 이상이어야 하고 items 는 비어 있지 않다).
        MemoryPageResponse page = memoryService.list(null, null, null, 50);
        assertFalse(page.items().isEmpty());
        assertNotNull(page.counts());
        assertTrue(page.counts().total() >= 5);
    }

    @Test
    @DisplayName("잘못된 커서·유형은 400")
    void rejectsBadCursorAndType() {
        assertThrows(
                BadRequestException.class,
                () -> memoryService.list(null, null, "!!!broken!!!", 20));

        assertThrows(BadRequestException.class, () -> memoryService.list(null, "nope", null, 20));
    }

    @Test
    @DisplayName("limit 은 1~50 으로 클램프(과대 요청 방어)")
    void clampsLimit() {
        // limit=999 여도 우리 seed 는 5건뿐이라 전부 반환되고 nextCursor 는 null(한도 초과 안 함)
        MemoryPageResponse big = memoryService.list(TOK, null, null, 999);
        assertEquals(5, big.items().size());
        assertNull(big.nextCursor());

        // limit=1 이면 1건 + nextCursor 존재
        MemoryPageResponse one = memoryService.list(TOK, null, null, 1);
        assertEquals(1, one.items().size());
        assertFalse(one.nextCursor() == null);
    }

    @Test
    @DisplayName("상태 전이: 숨김→active 목록에서 빠지고 archived로 조회·복원, 폐기(incorrect), 잘못된 status·id는 400·404")
    void statusTransitionSoftRemoveRestoreDiscard() {
        Long id = memIds.get(0);

        // 숨김(archived): active 목록에서 빠지고 archived 조회에 뜬다(소프트 제거 — DB엔 보존).
        MemoryDetailResponse hidden = memoryService.updateStatus(id, "archived");
        assertEquals("archived", hidden.status());
        assertFalse(ids(memoryService.list(TOK, null, null, 50)).contains(id));
        assertTrue(ids(memoryService.list(TOK, null, null, 50, "archived")).contains(id));

        // 복원(active): 다시 active 목록에 나타난다.
        assertEquals("active", memoryService.updateStatus(id, "active").status());
        assertTrue(ids(memoryService.list(TOK, null, null, 50)).contains(id));

        // 폐기(incorrect): active에서 빠지고 incorrect 조회에 뜬다.
        memoryService.updateStatus(id, "incorrect");
        assertFalse(ids(memoryService.list(TOK, null, null, 50)).contains(id));
        assertTrue(ids(memoryService.list(TOK, null, null, 50, "incorrect")).contains(id));

        // 잘못된 status → 400(BadRequestException)
        assertThrows(BadRequestException.class, () -> memoryService.updateStatus(id, "deleted"));

        // 없는 id → 404(NotFoundException)
        assertThrows(
                NotFoundException.class,
                () -> memoryService.updateStatus(999_999_999L, "archived"));
    }
}
