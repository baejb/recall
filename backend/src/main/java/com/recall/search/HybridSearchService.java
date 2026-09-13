package com.recall.search;

import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.UserAiContext;
import com.recall.memory.MemoryAccess;
import com.recall.memory.StoredMemory;
import com.recall.memory.type.PlanContribution;
import com.recall.memory.type.SearchChannel;
import com.recall.search.repository.MemorySearchStore;
import com.recall.search.service.RrfFusion;
import com.recall.settings.EmbeddingStatus;
import com.recall.settings.SettingsService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 하이브리드 검색(R·W) — 질의를 vector 채널과 BM25 채널로 각각 검색하고, 유형별 채널 가중치(P)로 RRF 융합한다. 융합은 결정론(LLM 금지). 결과 없으면
 * 빈 리스트(상위가 "기록 없음"으로 처리).
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** 채널별로 융합 전에 가져올 후보 수. */
    private static final int CHANNEL_K = 20;

    // 벡터 채널이 안전한 유일한 임베딩 상태는 EmbeddingStatus.READY 다. REINDEXING(신구 모델 혼재)뿐 아니라
    // FAILED(재색인이 중간에 실패해 memory_embedding 에 신구 모델 벡터가 섞인 채로 남음)도 벡터 공간이 일관되지
    // 않아 격하 대상이다(불변 원칙: 조용한 실패 금지 — 상태로 동작을 바꾼다).
    // 그 어휘는 컬럼을 소유한 settings 도메인의 EmbeddingStatus 가 갖는다 — 전엔 이 서비스가 자기 private
    // 상수를 들고 있어서, 같은 어휘를 쓰는 세 모듈(settings·search·여기)이 각자 리터럴을 갖고 있었다.

    private final MemorySearchStore store;
    private final MemoryAccess memories;
    private final StrategyRegistry<PlanContribution> plans;
    private final SettingsService settings;

    public HybridSearchService(
            MemorySearchStore store,
            MemoryAccess memories,
            List<PlanContribution> planContributions,
            SettingsService settings) {
        this.store = store;
        this.memories = memories;
        this.plans = new StrategyRegistry<>(planContributions);
        this.settings = settings;
    }

    /**
     * 질문에 대한 유형별 하이브리드 검색 결과(융합 순위 순). 소유자는 {@code ctx.userId()}로 스코프한다(요청 입력 userId를 신뢰하지 않는다).
     *
     * <p>벡터 채널은 두 조건을 모두 만족해야 켜진다: (1) {@code ctx.embeddingReady()} — embedding 키 미설정이면 격하(BM25만,
     * 409 아님 — 키워드 검색만으로도 응답 가능하므로 요청을 막지 않는다). (2) embedding_status가 READY — REINDEXING(신구 모델
     * 혼재)·FAILED(재색인 중간 실패로 신구 모델 벡터 혼재)면 격하. 벡터 채널은 {@code ctx}에 바인딩된 {@link
     * com.recall.llm.EmbeddingClient}만 쓴다(주입된 전역 싱글턴 아님) — 사용자별 provider/키 교차유출 방지.
     */
    public List<StoredMemory> search(String question, MemoryType type, UserAiContext ctx) {
        long userId = ctx.userId();
        boolean vectorReady =
                ctx.embeddingReady()
                        && EmbeddingStatus.READY.equals(settings.embeddingStatus(userId));
        List<Long> vectorIds = vectorReady ? vectorChannel(question, type, ctx) : List.of();
        List<Long> bm25Ids = ids(store.searchByKeyword(userId, question, type, CHANNEL_K));

        // 채널 키가 enum 이라 전략이 준 가중치 키와 여기서 만드는 순위 키가 어긋날 수 없다 — 전에는 양쪽이
        // 각자 문자열 리터럴을 갖고 있어, 이름이 하나만 틀려도 RrfFusion 이 조용히 1.0 으로 격하했다.
        Map<SearchChannel, List<Long>> ranked =
                Map.of(
                        SearchChannel.MEMORY_VECTOR, vectorIds,
                        SearchChannel.MEMORY_BM25, bm25Ids);
        Map<SearchChannel, Double> weights = plans.get(type).channelWeights();
        List<Long> fused = RrfFusion.fuse(ranked, weights);
        return loadInOrder(userId, fused);
    }

    /**
     * 벡터 채널 검색. 설정은 됐으나(embeddingReady + READY) 외부 임베딩 호출/벡터 검색이 <b>일시 실패</b>하면 예외를 위로 던져 질의 전체를
     * 죽이지 않고, 벡터 채널만 격하해 빈 결과를 돌린다 — BM25 채널만으로 응답한다(설계 §4 "벡터 실패→BM25", 조용한 실패 금지: 로그로 드러냄). 이는
     * 미설정(차단)과 다른 상황(외부 장애 격하)이다.
     */
    private List<Long> vectorChannel(String question, MemoryType type, UserAiContext ctx) {
        try {
            return ids(
                    store.searchByVector(
                            ctx.userId(), ctx.embedding().embedQuery(question), type, CHANNEL_K));
        } catch (RuntimeException e) {
            log.warn("벡터 채널 격하(외부 임베딩/검색 실패) → BM25만: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<Long> ids(List<ScoredMemory> scored) {
        return scored.stream().map(ScoredMemory::memoryId).toList();
    }

    /**
     * 융합 순위를 유지한 채 카드를 로드한다.
     *
     * <p>순서 유지는 memory 모듈의 계약({@code byIdsInOrder})이 보장한다 — 전에는 이 서비스가 남의 리포지토리를 직접 잡고 {@code
     * findAllById} 의 순서 미보장을 여기서 손으로 되돌렸다. 그 보정은 조회를 소유한 쪽에 있어야, 다른 호출자가 같은 함정을 다시 밟지 않는다.
     */
    private List<StoredMemory> loadInOrder(long userId, List<Long> ids) {
        return ids.isEmpty() ? List.of() : memories.byIdsInOrder(userId, ids);
    }
}
