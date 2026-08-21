package com.recall.search;

import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
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
    private final EmbeddingClient embeddingClient;
    private final MemoryRepository memoryRepository;
    private final StrategyRegistry<PlanContribution> plans;
    private final SettingsService settings;

    public HybridSearchService(
            MemorySearchStore store,
            EmbeddingClient embeddingClient,
            MemoryRepository memoryRepository,
            List<PlanContribution> planContributions,
            SettingsService settings) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.memoryRepository = memoryRepository;
        this.plans = new StrategyRegistry<>(planContributions);
        this.settings = settings;
    }

    /**
     * 질문에 대한 유형별 하이브리드 검색 결과(융합 순위 순). embedding_status가 READY가 아니면(REINDEXING: 신구 모델 혼재, FAILED:
     * 재색인이 중간에 실패해 신구 모델 벡터 혼재) 벡터 채널을 건너뛰고 BM25만 사용한다(격하).
     */
    public List<Memory> search(String question, MemoryType type) {
        boolean vectorReady = STATUS_READY.equals(settings.embeddingStatus());
        List<Long> vectorIds =
                vectorReady
                        ? ids(
                                store.searchByVector(
                                        embeddingClient.embedQuery(question), type, CHANNEL_K))
                        : List.of();
        List<Long> bm25Ids = ids(store.searchByKeyword(question, type, CHANNEL_K));

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
