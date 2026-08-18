package com.recall.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.capture.Capture;
import com.recall.capture.CaptureRepository;
import com.recall.common.MemoryType;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.ScoredMemory;
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

/**
 * 🔴 하이브리드 검색의 DB 레벨 user_id 필터(raw SQL)를 두 사용자 DB에 대고 실제로 실행해 교차유출을 막는지 검증한다. 이 SQL(WHERE
 * m.user_id = ?)은 검색(R)·유사판정(S4)·질의 답변(A)이 공유하는 실제 유출면이라, mock 이 아닌 실 DB로 파티셔닝을
 * 증명한다(HybridSearchDegradeTest 는 store 를 mock 해서 이 WHERE 절을 한 번도 실행하지 않는다).
 */
@Tag("release-gate")
@SpringBootTest
class SearchStoreIsolationTest {

    private static final String SHARED_KEYWORD = "라우팅";

    @Autowired private MemorySearchStore searchStore;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private MemoryRepository memoryRepository;
    @Autowired private JdbcTemplate jdbc;

    private long userA;
    private long userB;
    private long memoryA;
    private long memoryB;
    private final List<Long> captureIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        userA = seedUser("search-a");
        userB = seedUser("search-b");
        // 두 사용자에게 같은 키워드·벡터의 active 기억을 심는다 — user_id 필터가 없으면 둘 다 매치된다.
        memoryA = seedIndexedMemory(userA);
        memoryB = seedIndexedMemory(userB);
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

    private long seedIndexedMemory(long userId) {
        Capture capture = captureRepository.save(new Capture(userId, "chat", "원문", "[]"));
        captureIds.add(capture.getId());
        Memory memory =
                memoryRepository.save(
                        new Memory(capture, MemoryType.KNOWLEDGE, SHARED_KEYWORD, "{}"));
        long memoryId = memory.getId();
        // BM25 tsv + 벡터 지문을 채워 두 채널 모두 매치되게 한다.
        searchStore.updateSearchTsv(memoryId, SHARED_KEYWORD);
        float[] vector = new float[1024];
        vector[0] = 1f;
        searchStore.saveEmbedding(memoryId, "document", vector);
        return memoryId;
    }

    @AfterEach
    void cleanup() {
        captureRepository.deleteAllById(captureIds);
        captureIds.clear();
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        userIds.clear();
    }

    @Test
    @DisplayName("🔴 BM25 키워드 검색은 소유자 기억만 — 남의 매치가 섞이지 않는다")
    void keywordSearchIsScopedToOwner() {
        List<Long> idsA =
                ids(searchStore.searchByKeyword(userA, SHARED_KEYWORD, MemoryType.KNOWLEDGE, 10));
        assertEquals(List.of(memoryA), idsA, "A 검색은 A 기억만");
        assertTrue(!idsA.contains(memoryB), "A 는 B 의 기억을 검색하면 안 된다(교차유출)");

        List<Long> idsB =
                ids(searchStore.searchByKeyword(userB, SHARED_KEYWORD, MemoryType.KNOWLEDGE, 10));
        assertEquals(List.of(memoryB), idsB, "B 검색은 B 기억만");
    }

    @Test
    @DisplayName("🔴 벡터 검색은 소유자 기억만 — 남의 근접 벡터가 섞이지 않는다")
    void vectorSearchIsScopedToOwner() {
        float[] query = new float[1024];
        query[0] = 1f;
        List<Long> idsA = ids(searchStore.searchByVector(userA, query, MemoryType.KNOWLEDGE, 10));
        assertEquals(List.of(memoryA), idsA, "A 벡터검색은 A 기억만");
        assertTrue(!idsA.contains(memoryB), "A 는 B 의 벡터를 검색하면 안 된다(교차유출)");
    }

    private static List<Long> ids(List<ScoredMemory> scored) {
        return scored.stream().map(ScoredMemory::memoryId).toList();
    }
}
