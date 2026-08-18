package com.recall.search;

import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.llm.UserAiContext;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.ScoredMemory;
import com.recall.memory.type.PlanContribution;
import com.recall.settings.SettingsService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 하이브리드 검색(R·W) — 질의를 vector 채널과 BM25 채널로 각각 검색하고, 유형별 채널 가중치(P)로 RRF 융합한다. 융합은 결정론(LLM 금지). 결과 없으면
 * 빈 리스트(상위가 "기록 없음"으로 처리).
 */
@Service
public class HybridSearchService {

    /** 채널별로 융합 전에 가져올 후보 수. */
    private static final int CHANNEL_K = 20;

    /**
     * 벡터 채널이 안전한 유일한 임베딩 상태. REINDEXING(신구 모델 혼재)뿐 아니라 FAILED(재색인이 중간에 실패해 memory_embedding 이 신구 모델
     * 벡터가 섞인 채로 남음)도 벡터 공간이 일관되지 않아 격하 대상이다(불변 원칙: 조용한 실패 금지 — 상태로 동작을 바꾼다).
     */
    private static final String STATUS_READY = "READY";

    private static final String CH_VECTOR = "memory_vector";
    private static final String CH_BM25 = "memory_bm25";

    private final MemorySearchStore store;
    private final MemoryRepository memoryRepository;
    private final StrategyRegistry<PlanContribution> plans;
    private final SettingsService settings;

    public HybridSearchService(
            MemorySearchStore store,
            MemoryRepository memoryRepository,
            List<PlanContribution> planContributions,
            SettingsService settings) {
        this.store = store;
        this.memoryRepository = memoryRepository;
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
    public List<Memory> search(String question, MemoryType type, UserAiContext ctx) {
        long userId = ctx.userId();
        boolean vectorReady =
                ctx.embeddingReady() && STATUS_READY.equals(settings.embeddingStatus(userId));
        List<Long> vectorIds =
                vectorReady
                        ? ids(
                                store.searchByVector(
                                        userId,
                                        ctx.embedding().embedQuery(question),
                                        type,
                                        CHANNEL_K))
                        : List.of();
        List<Long> bm25Ids = ids(store.searchByKeyword(userId, question, type, CHANNEL_K));

        Map<String, List<Long>> ranked = Map.of(CH_VECTOR, vectorIds, CH_BM25, bm25Ids);
        Map<String, Double> weights = plans.get(type).channelWeights();
        List<Long> fused = RrfFusion.fuse(ranked, weights);
        return loadInOrder(fused);
    }

    private static List<Long> ids(List<ScoredMemory> scored) {
        return scored.stream().map(ScoredMemory::memoryId).toList();
    }

    /** 융합 순위를 유지한 채 memory 엔티티를 로드한다(findAllById는 순서를 보장하지 않음). */
    private List<Memory> loadInOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Memory> byId =
                memoryRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Memory::getId, m -> m));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
