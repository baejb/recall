package com.recall.search;

import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SearchRepresentation;
import com.recall.search.repository.MemorySearchStore;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 검색 인덱스에 대한 <b>search 모듈의 공개 계약</b> — 색인 갱신과 채널별 후보 조회.
 *
 * <p><b>왜 이 클래스가 생겼나</b> — 인덱스 테이블({@code memory_embedding} · {@code memory.search_tsv})을 다루는 저장소가
 * memory 모듈에 있었고, 그걸 search·store·review 세 모듈이 <b>각자 직접</b> 잡고 있었다. 그래서 "카드를 색인한다"는 절차가 두 곳에 복제됐다:
 * 승인 경로({@code ReviewService})와 재색인 경로({@code ReindexService})가 각자 {@code SearchRepresentation} 을
 * 조회해 임베딩을 저장했다.
 *
 * <p>복제의 대가는 <b>규약이 갈라지는 것</b>이다. 예를 들어 "색인 텍스트에 무엇을 넣는가"(제목·요약·키워드)를 한쪽에서 바꾸면 다른 쪽은 조용히 옛 규약으로 남는다
 * — 같은 카드가 승인 시점과 재색인 이후에 다르게 검색된다. 인덱스를 소유한 모듈이 색인 절차도 소유하면 그 갈라짐이 구조적으로 불가능해진다.
 *
 * <p>저장소({@link MemorySearchStore})는 {@code search/repository} 로 내려가 이 모듈 내부가 되고, 다른 모듈은 이 계약만 본다.
 */
@Service
public class SearchIndex {

    private final MemorySearchStore store;
    private final StrategyRegistry<SearchRepresentation> representations;

    public SearchIndex(
            MemorySearchStore store, List<SearchRepresentation> representationStrategies) {
        this.store = store;
        this.representations = new StrategyRegistry<>(representationStrategies);
    }

    /**
     * 승인 직후의 최초 색인 — BM25 텍스트와 벡터를 함께 넣는다.
     *
     * <p>실패를 삼키지 않는다. 승인 경로는 "memory 는 유지하고 색인만 포기"가 맞고 재색인 경로는 "잡을 FAILED 로 드러낸다"가 맞아, 실패를 어떻게
     * 취급할지는 <b>호출자의 판단</b>이다. 여기서 잡아 버리면 재색인이 실패를 못 본다.
     */
    public void indexApproved(
            long memoryId, MemoryType type, MemoryCard card, EmbeddingClient embedding) {
        store.updateSearchTsv(memoryId, keywordText(card));
        reembed(memoryId, type, card, embedding);
    }

    /**
     * 벡터만 다시 넣는다(재색인).
     *
     * <p>BM25 텍스트는 건드리지 않는다 — 카드 내용이 그대로인데 임베딩 모델만 바뀐 상황이라, tsvector 를 다시 쓰는 것은 같은 값을 쓰는 낭비다.
     */
    public void reembed(
            long memoryId, MemoryType type, MemoryCard card, EmbeddingClient embedding) {
        Map<String, String> texts = representations.get(type).embeddingTexts(card);
        texts.forEach(
                (kind, text) -> store.saveEmbedding(memoryId, kind, embedding.embedDocument(text)));
    }

    /**
     * 이 memory 의 벡터를 모두 지운다.
     *
     * <p>낡은 벡터를 남기면 신구 모델이 한 공간에 섞여 {@code READY} 가 거짓이 된다. 지워서 그 카드만 BM25 전용으로 격하하고 나머지 벡터 공간의
     * 일관성을 지킨다.
     */
    public void clearEmbeddings(long memoryId) {
        store.deleteEmbeddings(memoryId);
    }

    /** 벡터 채널 상위 k. {@code kind} 가 null 이면 모든 kind(조회 경로), 지정하면 그 표현끼리만 대조(S4 경로). */
    public List<ScoredMemory> topByVector(
            long userId, float[] query, MemoryType type, String kind, int k) {
        return store.searchByVector(userId, query, type, kind, k);
    }

    /** BM25 채널 상위 k. */
    public List<ScoredMemory> topByKeyword(long userId, String query, MemoryType type, int k) {
        return store.searchByKeyword(userId, query, type, k);
    }

    /**
     * BM25 색인 텍스트 — 제목 + 요약 + 키워드.
     *
     * <p>카드 전문을 넣지 않는다: 원문을 그대로 담은 fallback 카드가 있으면 그 카드의 {@code search_tsv} 가 대화 전체 어휘를 갖게 되고, 그
     * 유형의 거의 모든 질문에서 BM25 상위를 차지한다.
     */
    private static String keywordText(MemoryCard card) {
        StringBuilder sb = new StringBuilder();
        append(sb, card.title());
        append(sb, card.summary());
        card.keywords().forEach(keyword -> append(sb, keyword));
        return sb.toString().strip();
    }

    private static void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value).append(' ');
        }
    }
}
