package com.recall.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.capture.Capture;
import com.recall.capture.CaptureRepository;
import com.recall.common.MemoryType;
import com.recall.llm.AiContextFactory;
import com.recall.llm.StubEmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.query.QueryPipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 🔴 저장 파이프라인 판정(S4, {@link SimilarMemoryFinder})과 조회 파이프라인 검색({@link QueryPipeline#retrieve})의
 * 멀티유저 격리. 남의 기억이 유사 후보로 끌려오면 잘못된 재발/충돌 판정이 나고, 남의 기억이 검색 근거로 잡히면 답변이 남의 내용을 노출한다.
 *
 * <p>확인 방식: B 에게만 색인된 기억을 심고, 소유자 A 로 두 진입점을 부르면 아무것도 안 잡혀야 한다(A 는 데이터가 없다). B 로 부르면 자기 기억이 잡힌다(설정이
 * 실제로 색인됐음을 보증 — 거짓 통과 방지).
 */
@Tag("release-gate")
@SpringBootTest
class StoreIsolationTest {

    private static final String KEYWORD = "라우팅격리";

    @Autowired private SimilarMemoryFinder similarMemoryFinder;
    @Autowired private QueryPipeline queryPipeline;
    @Autowired private AiContextFactory contextFactory;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private MemoryRepository memoryRepository;
    @Autowired private MemorySearchStore searchStore;
    @Autowired private JdbcTemplate jdbc;

    private long userA;
    private long userB;
    private final List<Long> captureIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        userA = seedUser("store-a"); // 데이터 없음
        userB = seedUser("store-b");
        seedIndexedMemory(userB); // B 에게만 색인된 기억
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

    private void seedIndexedMemory(long userId) {
        Capture capture = captureRepository.save(new Capture(userId, "chat", "원문", "[]"));
        captureIds.add(capture.getId());
        Memory memory =
                memoryRepository.save(new Memory(capture, MemoryType.KNOWLEDGE, KEYWORD, "{}"));
        searchStore.updateSearchTsv(memory.getId(), KEYWORD);
        float[] vector = new float[1024];
        vector[0] = 1f;
        searchStore.saveEmbedding(memory.getId(), "document", vector);
    }

    @AfterEach
    void cleanup() {
        captureRepository.deleteAllById(captureIds);
        captureIds.clear();
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        userIds.clear();
    }

    @Test
    @DisplayName("🔴 S4 유사판정: 소유자(A)에겐 남(B)의 기억이 후보로 안 잡힌다")
    void similarFinderScopedToOwner() {
        Map<String, Object> structured = Map.of("title", KEYWORD);
        // embedding 만 필요(chat 은 이 경로에서 쓰이지 않음) — StubEmbeddingClient 로 기존 동작(0벡터→BM25 폴백)과 동일하게 유지.
        UserAiContext embeddingCtx =
                new UserAiContext(0L, null, new StubEmbeddingClient(), true, true);
        Optional<Memory> forA =
                similarMemoryFinder.findSimilar(
                        userA, structured, MemoryType.KNOWLEDGE, embeddingCtx);
        assertTrue(forA.isEmpty(), "A 는 B 의 기억을 유사 후보로 끌어오면 안 된다(교차유출)");

        // 거짓 통과 방지: B 로 부르면 자기 기억이 잡힌다(색인이 실제로 됐음).
        Optional<Memory> forB =
                similarMemoryFinder.findSimilar(
                        userB, structured, MemoryType.KNOWLEDGE, embeddingCtx);
        assertTrue(forB.isPresent(), "B 는 자기 색인 기억을 후보로 찾는다");
    }

    @Test
    @DisplayName("🔴 조회 검색(R): 소유자(A)에겐 남(B)의 기억이 근거로 안 잡힌다")
    void retrieveScopedToOwner() {
        // retrieve는 chat이 아니라 embedding capability만 쓰므로, chat 미설정인 ctx(둘 다 model_setting
        // 행 없음)라도 그대로 통과한다 — 차단은 조회 입구(QueryController)의 몫이지 R 단계의 몫이 아니다.
        UserAiContext ctxA = contextFactory.forUser(userA);
        UserAiContext ctxB = contextFactory.forUser(userB);

        List<Memory> forA = queryPipeline.retrieve(KEYWORD, MemoryType.KNOWLEDGE, ctxA);
        assertTrue(forA.isEmpty(), "A 검색은 B 의 기억을 근거로 반환하면 안 된다(교차유출)");

        List<Memory> forB = queryPipeline.retrieve(KEYWORD, MemoryType.KNOWLEDGE, ctxB);
        assertTrue(!forB.isEmpty(), "B 검색은 자기 기억을 근거로 반환한다");
    }
}
