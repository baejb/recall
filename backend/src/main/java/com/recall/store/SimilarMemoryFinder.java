package com.recall.store;

import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.ScoredMemory;
import com.recall.memory.type.SearchRepresentation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 저장 경로에서 신규 추출(proposed)과 유사한 기존 memory 후보를 찾는다(S4 판정 입력).
 *
 * <p>문서 임베딩 코사인 유사도(문서 vs 문서)로 τ_sim 이상 최상위를 고르고, 없으면 BM25로 폴백한다(임베딩이 stub이거나 벡터가 무의미할 때 후보라도 확보).
 * 결정 임계(τ_sim)는 매직넘버 금지 원칙에 따라 상수로 둔다.
 */
@Component
public class SimilarMemoryFinder {

    /** 코사인 유사도 임계(1=동일). 이보다 낮으면 "유사 후보 아님"으로 본다. 라벨셋 fit로 튜닝 대상. */
    private static final double TAU_SIM = 0.75;

    /** 채널별로 살펴볼 후보 수. */
    private static final int K = 5;

    private final EmbeddingClient embeddingClient;
    private final MemorySearchStore searchStore;
    private final MemoryRepository memoryRepository;
    private final StrategyRegistry<SearchRepresentation> searchReps;

    public SimilarMemoryFinder(
            EmbeddingClient embeddingClient,
            MemorySearchStore searchStore,
            MemoryRepository memoryRepository,
            List<SearchRepresentation> searchRepresentations) {
        this.embeddingClient = embeddingClient;
        this.searchStore = searchStore;
        this.memoryRepository = memoryRepository;
        this.searchReps = new StrategyRegistry<>(searchRepresentations);
    }

    /** proposed와 유사한 기존 active memory 최상위 후보(없으면 empty). */
    public Optional<Memory> findSimilar(Map<String, Object> structured, MemoryType type) {
        String text = representativeText(structured, type);
        if (text.isBlank()) {
            return Optional.empty();
        }

        float[] vector = embeddingClient.embedDocument(text);
        Optional<Long> byVector =
                searchStore.searchByVector(vector, type, K).stream()
                        .filter(s -> s.score() >= TAU_SIM)
                        .map(ScoredMemory::memoryId)
                        .findFirst();
        if (byVector.isPresent()) {
            return memoryRepository.findById(byVector.get());
        }

        return searchStore.searchByKeyword(text, type, K).stream()
                .map(ScoredMemory::memoryId)
                .findFirst()
                .flatMap(memoryRepository::findById);
    }

    /** 유사 판정에 쓸 대표 텍스트 — 유형별 검색 표현의 document(비면 title). */
    private String representativeText(Map<String, Object> structured, MemoryType type) {
        String document = searchReps.get(type).embeddingTexts(structured).get("document");
        if (document != null && !document.isBlank()) {
            return document;
        }
        Object title = structured.get("title");
        return title == null ? "" : title.toString().strip();
    }
}
