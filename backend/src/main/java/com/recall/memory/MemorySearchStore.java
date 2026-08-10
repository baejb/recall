package com.recall.memory;

import com.recall.common.MemoryType;
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

    /** BM25용 tsvector 갱신(한국어 형태소 사전이 없어 simple 구성 사용). */
    public void updateSearchTsv(Long memoryId, String text) {
        jdbc.update(
                "UPDATE memory SET search_tsv = to_tsvector('simple', ?) WHERE id = ?",
                text,
                memoryId);
    }

    /** 코사인 유사도(1 - 거리) 상위 k. memory별 여러 kind 중 가장 가까운 값으로 집계. */
    public List<ScoredMemory> searchByVector(float[] query, MemoryType type, int k) {
        return jdbc.query(
                "SELECT e.memory_id AS id, MAX(1 - (e.vector <=> CAST(? AS vector))) AS score "
                        + "FROM memory_embedding e JOIN memory m ON m.id = e.memory_id "
                        + "WHERE m.type = ? AND m.status = 'active' "
                        + "GROUP BY e.memory_id ORDER BY score DESC LIMIT ?",
                (rs, i) -> new ScoredMemory(rs.getLong("id"), rs.getDouble("score")),
                toVectorLiteral(query),
                type.name(),
                k);
    }

    /** 전문검색(BM25 유사) 상위 k. */
    public List<ScoredMemory> searchByKeyword(String query, MemoryType type, int k) {
        return jdbc.query(
                "SELECT id, ts_rank(search_tsv, plainto_tsquery('simple', ?)) AS score "
                        + "FROM memory "
                        + "WHERE type = ? AND status = 'active' "
                        + "AND search_tsv @@ plainto_tsquery('simple', ?) "
                        + "ORDER BY score DESC LIMIT ?",
                (rs, i) -> new ScoredMemory(rs.getLong("id"), rs.getDouble("score")),
                query,
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
