package com.recall.search.repository;

import com.recall.common.type.MemoryType;
import com.recall.memory.MemoryStatus;
import com.recall.search.ScoredMemory;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 검색 인덱스(pgvector {@code memory_embedding} + {@code memory.search_tsv})를 채우고 조회하는 창구. 벡터 타입·전문검색은
 * 파생 메서드로 표현할 수 없어 native SQL로 다룬다(JPA 엔티티 없이 JdbcTemplate). 결정론 단계라 LLM을 쓰지 않는다.
 */
@Repository
public class MemorySearchStore {

    private final JdbcTemplate jdbc;

    public MemorySearchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** kind별 임베딩 저장(승인 재실행 대비 upsert). */
    public void saveEmbedding(Long memoryId, String kind, float[] vector) {
        jdbc.update(
                "INSERT INTO memory_embedding(memory_id, kind, vector) VALUES (?, ?, CAST(? AS vector)) "
                        + "ON CONFLICT (memory_id, kind) DO UPDATE SET vector = EXCLUDED.vector",
                memoryId,
                kind,
                toVectorLiteral(vector));
    }

    /**
     * 한 memory 의 임베딩 전부 삭제.
     *
     * <p>재색인이 카드를 읽을 수 없어 새 벡터를 만들 수 없을 때 쓴다 — 낡은 벡터를 남기면 신구 모델이 섞여 { embedding_status=READY} 가 거짓이
     * 된다(벡터 공간 일관성이 READY 의 의미다). 지워서 그 카드만 BM25 전용으로 격하하고 나머지 공간의 일관성을 지킨다. memory 자체는 지우지 않는다(삭제
     * 대신 상태 보존은 memory 에 대한 규칙이고, 임베딩은 언제든 다시 만드는 파생 산물이다).
     */
    public void deleteEmbeddings(Long memoryId) {
        jdbc.update("DELETE FROM memory_embedding WHERE memory_id = ?", memoryId);
    }

    /** BM25용 tsvector 갱신(한국어 형태소 사전이 없어 simple 구성 사용). */
    public void updateSearchTsv(Long memoryId, String text) {
        jdbc.update(
                "UPDATE memory SET search_tsv = to_tsvector('simple', ?) WHERE id = ?",
                text,
                memoryId);
    }

    /**
     * 코사인 유사도(1 - 거리) 상위 k — <b>모든 kind</b> 중 가장 가까운 값으로 집계. 조회 경로(질문 검색)가 쓴다: 트러블슈팅은 "이런 증상이었는데"가
     * problem 에, "결국 어떻게 고쳤지"가 solution 에 걸려야 하므로 kind 를 가리지 않는 게 맞다. memory.user_id 필터로 소유자
     * 스코프(교차유출 금지 — memory_embedding 은 user_id 를 두지 않고 memory join 으로 격리한다).
     */
    public List<ScoredMemory> searchByVector(long userId, float[] query, MemoryType type, int k) {
        return searchByVector(userId, query, type, null, k);
    }

    /**
     * 위와 같지만 {@code kind}가 주어지면 <b>그 kind 벡터만</b> 대조한다.
     *
     * <p>kind 스코프가 필요한 이유: S4 유사 판정({@link com.recall.store.SimilarMemoryFinder})은 "같은 문제인가"를 물으므로
     * 신규 후보의 problem 텍스트를 기존 카드의 <b>problem</b> 과만 대조해야 한다. 그런데 kind 필터가 없으면 {@code MAX()} 집계가 기존
     * 카드의 <b>solution</b> 벡터와의 유사도까지 후보로 올린다 — "파드가 OOMKilled 로 재시작"(신규 증상)이 "메모리 limit 을 올려
     * OOMKilled 재시작을 막았다"(기존 해결책)와 τ_sim 을 넘겨, 판정 프롬프트가 설계하지 않은 증상 vs 해결책 짝을 S4 에 넘기고 있었다. 지식 유형도
     * 마찬가지로 javadoc 이 주장하는 "문서 vs 문서"가 실제로는 문서 vs (문서 ∪ 사실)이었다. 호출부가 어떤 kind 로 물었는지를 SQL 까지 관통시켜
     * 문서화된 의도와 동작을 맞춘다.
     *
     * @param kind 대조할 임베딩 kind. {@code null}이면 모든 kind(조회 경로).
     */
    public List<ScoredMemory> searchByVector(
            long userId, float[] query, MemoryType type, String kind, int k) {
        String sql =
                "SELECT e.memory_id AS id, MAX(1 - (e.vector <=> CAST(? AS vector))) AS score "
                        + "FROM memory_embedding e JOIN memory m ON m.id = e.memory_id "
                        + "WHERE m.user_id = ? AND m.type = ? AND m.status = '"
                        + MemoryStatus.ACTIVE
                        + "' "
                        + (kind == null ? "" : "AND e.kind = ? ")
                        + "GROUP BY e.memory_id ORDER BY score DESC LIMIT ?";
        Object[] args =
                kind == null
                        ? new Object[] {toVectorLiteral(query), userId, type.name(), k}
                        : new Object[] {toVectorLiteral(query), userId, type.name(), kind, k};
        return jdbc.query(
                sql, (rs, i) -> new ScoredMemory(rs.getLong("id"), rs.getDouble("score")), args);
    }

    /**
     * 전문검색(BM25 유사) 상위 k. 질의 토큰을 OR로 결합해 부분 매칭한다. plainto_tsquery는 토큰을 AND로 묶어, 형태소 사전이 없는 한국어에선 조사
     * 차이(방법 vs 방법이다)로 매칭이 거의 안 되기 때문이다.
     */
    public List<ScoredMemory> searchByKeyword(long userId, String query, MemoryType type, int k) {
        // 질의를 tsvector로 정규화 → lexeme들을 ' | '(OR)로 이어 tsquery 생성.
        String orQuery =
                "to_tsquery('simple', array_to_string(tsvector_to_array(to_tsvector('simple', ?)), ' | '))";
        return jdbc.query(
                "SELECT id, ts_rank(search_tsv, "
                        + orQuery
                        + ") AS score "
                        + "FROM memory "
                        + "WHERE user_id = ? AND type = ? AND status = '"
                        + MemoryStatus.ACTIVE
                        + "' "
                        + "AND search_tsv @@ "
                        + orQuery
                        + " ORDER BY score DESC LIMIT ?",
                (rs, i) -> new ScoredMemory(rs.getLong("id"), rs.getDouble("score")),
                query,
                userId,
                type.name(),
                query,
                k);
    }

    /** float[] → pgvector 리터럴 {@code "[f1,f2,…]"}. */
    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
