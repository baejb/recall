package com.recall.store;

import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.UserAiContext;
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

    private final MemorySearchStore searchStore;
    private final MemoryRepository memoryRepository;
    private final StrategyRegistry<SearchRepresentation> searchReps;

    public SimilarMemoryFinder(
            MemorySearchStore searchStore,
            MemoryRepository memoryRepository,
            List<SearchRepresentation> searchRepresentations) {
        this.searchStore = searchStore;
        this.memoryRepository = memoryRepository;
        this.searchReps = new StrategyRegistry<>(searchRepresentations);
    }

    /**
     * proposed와 유사한 기존 active memory 최상위 후보(없으면 empty). 판정(S4)은 같은 사용자의 기억끼리만 대조한다 — userId 는 처리 중인
     * 원문 (capture)의 소유자다(교차유출 금지). 임베딩은 {@code ctx.requireEmbedding()}으로 얻는다 — 주입된 전역 싱글턴이 아니라
     * capture 소유자에 바인딩된 클라이언트만 쓴다.
     *
     * <p>후보 재조회는 {@code findByIdAndUserId}로 소유자 조건을 끝까지 유지한다 — 검색 인덱스(searchStore)가 이미 userId로
     * 스코프하지만, 재조회 지점에서도 owner 조건을 한 번 더 강제해 교차유출 방어를 이중화한다(회귀 가드).
     */
    public Optional<Memory> findSimilar(
            long userId, Map<String, Object> structured, MemoryType type, UserAiContext ctx) {
        String text = representativeText(structured, type);
        if (text.isBlank()) {
            return Optional.empty();
        }

        EmbeddingClient embeddingClient = ctx.requireEmbedding();
        float[] vector = embeddingClient.embedDocument(text);
        Optional<Long> byVector =
                searchStore.searchByVector(userId, vector, type, K).stream()
                        .filter(s -> s.score() >= TAU_SIM)
                        .map(ScoredMemory::memoryId)
                        .findFirst();
        if (byVector.isPresent()) {
            return memoryRepository.findByIdAndUserId(byVector.get(), userId);
        }

        return searchStore.searchByKeyword(userId, text, type, K).stream()
                .map(ScoredMemory::memoryId)
                .findFirst()
                .flatMap(id -> memoryRepository.findByIdAndUserId(id, userId));
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
