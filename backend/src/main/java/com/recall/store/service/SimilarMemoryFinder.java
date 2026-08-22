package com.recall.store.service;

import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.repository.MemoryRepository;
import com.recall.memory.repository.MemorySearchStore;
import com.recall.memory.service.entity.Memory;
import com.recall.memory.service.entity.ScoredMemory;
import com.recall.memory.type.EmbeddingKind;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SearchRepresentation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 저장 경로에서 신규 추출(proposed)과 유사한 기존 memory 후보를 찾는다(S4 판정 입력).
 *
 * <p>문서 임베딩 코사인 유사도(문서 vs 문서)로 τ_sim 이상 최상위를 고르고, 없으면 BM25로 폴백한다(임베딩이 stub이거나 벡터가 무의미할 때 후보라도 확보).
 * 결정 임계(τ_sim)는 매직넘버 금지 원칙에 따라 상수로 둔다.
 */
@Component
public class SimilarMemoryFinder {

    private static final Logger log = LoggerFactory.getLogger(SimilarMemoryFinder.class);

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
            long userId, MemoryCard card, MemoryType type, UserAiContext ctx) {
        Representative representative = representative(card, type);
        String text = representative.text();
        if (text.isBlank()) {
            return Optional.empty();
        }

        EmbeddingClient embeddingClient = ctx.requireEmbedding();
        float[] vector = embeddingClient.embedDocument(text);
        // 대표 kind 를 SQL 까지 넘겨 같은 kind 끼리만 대조한다 — kind 를 안 넘기면 MAX() 집계가 기존 카드의
        // 다른 kind(트러블슈팅 solution, 지식 fact)와의 유사도까지 후보로 올려, 판정 프롬프트가 설계하지 않은
        // 짝(증상 vs 해결책)을 S4 에 넘긴다. title 폴백은 어느 kind 도 대표하지 않으므로 kind 를 걸지 않는다.
        //
        // kind 스코프의 대가(주석에 없던 사실): 이 필터는 "잘못된 짝"만 빼는 게 아니라, **대표 kind 임베딩이
        // 없는 기존 카드를 벡터 단계에서 아예 안 보이게** 만든다. 지식 유형에서 신규 카드는 document 가 있고
        // (대표 kind=document) 기존 중복 카드는 facts 만 있으면(document 공백 → document 임베딩 미생성)
        // 그 둘은 벡터로 대조될 수 없다. 질의 벡터가 하나뿐이라 kind 마다 임베딩을 따로 만들지 않는 한
        // 구조적으로 남는 한계다(그러면 capture 당 임베딩 호출이 kind 수만큼 늘어난다).
        Optional<Long> byVector =
                searchStore.searchByVector(userId, vector, type, representative.kind(), K).stream()
                        .filter(s -> s.score() >= TAU_SIM)
                        .map(ScoredMemory::memoryId)
                        .findFirst();
        Optional<Long> byKeyword =
                searchStore.searchByKeyword(userId, text, type, K).stream()
                        .map(ScoredMemory::memoryId)
                        .findFirst();

        if (byVector.isPresent()) {
            // 위 한계가 실제로 물렸을 수 있는 지점을 드러낸다: BM25 가 다른 카드를 최상위로 꼽았다면
            // 벡터가 놓친 진짜 중복이 그쪽일 수 있다(대표 kind 임베딩이 없어 안 보였을 수 있으므로).
            // 판정에는 하나만 넘길 수 있어 의미 유사(벡터)를 택하지만, 어긋남 자체는 조용히 넘기지 않는다.
            if (byKeyword.isPresent() && !byKeyword.get().equals(byVector.get())) {
                log.warn(
                        "S4 후보 불일치: 벡터={} BM25={} (kind={}) — 벡터 후보로 판정한다."
                                + " 대표 kind 임베딩이 없는 카드는 벡터에서 안 보인다는 한계일 수 있다.",
                        byVector.get(),
                        byKeyword.get(),
                        representative.kind());
            }
            return memoryRepository.findByIdAndUserId(byVector.get(), userId);
        }

        return byKeyword.flatMap(id -> memoryRepository.findByIdAndUserId(id, userId));
    }

    /**
     * 유사 판정에 쓸 대표 텍스트와 그 텍스트가 속한 kind.
     *
     * <p>kind 를 텍스트와 함께 돌려주는 이유: 이전에는 텍스트만 돌려주고 벡터 검색에는 kind 를 넘기지 않아, "problem 끼리 대조한다"는 이 메서드의
     * 규약이 SQL 에서 지켜지지 않았다(모든 kind 중 MAX). 어떤 kind 로 물었는지를 호출부가 알아야 같은 kind 로만 대조할 수 있다.
     *
     * @param kind 대조할 kind. title 폴백은 어떤 kind 의 임베딩도 아니므로 {@code null}(kind 무제한).
     */
    private record Representative(String kind, String text) {}

    /**
     * 유사 판정에 쓸 대표 텍스트 — 유형별 검색 표현이 내는 kind 중 하나를 고른다.
     *
     * <p>유형마다 대표 kind가 다르다: 지식은 {@code document}("문서 vs 문서" 대조), 트러블슈팅은 {@code problem}(같은 문제인지를
     * 증상·에러 시그니처로 먼저 본다 — 해결책이 달라도 같은 문제일 수 있으므로). 그래서 {@code document}가 있으면 그것을, 없으면 <b>첫
     * kind</b>를 쓴다(kind 순서는 유형 전략이 정한다 — 공유 코드가 유형을 알지 않게).
     *
     * <p>임베딩 대상 kind가 하나도 없으면 title로 폴백한다(후보를 아예 못 찾는 것보다 낫다). 이때는 대조할 kind 가 없으므로 kind 를 걸지 않는다 —
     * title 은 어떤 kind 벡터로도 저장되지 않기 때문이다.
     */
    private Representative representative(MemoryCard card, MemoryType type) {
        Map<String, String> texts = searchReps.get(type).embeddingTexts(card);
        // kind 이름을 리터럴로 적지 않는다 — "document" 는 카드 필드 이름·Voyage input type 과도 같은 문자열이라
        // 어떤 네임스페이스를 의도한 것인지 코드만 봐선 구분되지 않았다(EmbeddingKind 가 그 어휘를 소유한다).
        String document = texts.get(EmbeddingKind.DOCUMENT);
        if (document != null && !document.isBlank()) {
            return new Representative(EmbeddingKind.DOCUMENT, document);
        }
        Optional<Map.Entry<String, String>> firstKind =
                texts.entrySet().stream()
                        .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                        .findFirst();
        if (firstKind.isPresent()) {
            return new Representative(firstKind.get().getKey(), firstKind.get().getValue());
        }
        return new Representative(null, card.title());
    }
}
