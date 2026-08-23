package com.recall.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.type.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SearchRepresentation;
import com.recall.memory.type.knowledge.KnowledgeCard;
import com.recall.search.repository.MemorySearchStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 색인 절차의 규약 — <b>어떤 kind 로 무엇을 임베딩하고, 무엇을 BM25 텍스트로 넣는가</b>.
 *
 * <p>이 검증은 전에 {@code ReindexServiceTest} 안에 있었다(그쪽이 저장소를 직접 잡고 있었으니 kind 까지 볼 수 있었다). 절차를 인덱스 소유자에게
 * 모은 뒤로는 여기가 그 규약의 자리다 — 승인 경로와 재색인 경로가 같은 코드를 지나므로, 한 번 고정하면 두 경로에 동시에 걸린다.
 */
@Tag("unit")
class SearchIndexTest {

    private final MemorySearchStore store = mock(MemorySearchStore.class);
    private final SearchRepresentation representation = mock(SearchRepresentation.class);
    private final EmbeddingClient embedding = mock(EmbeddingClient.class);

    @BeforeEach
    void stubStrategy() {
        when(representation.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(embedding.embedDocument(any())).thenReturn(new float[1024]);
    }

    private SearchIndex index() {
        return new SearchIndex(store, List.of(representation));
    }

    private static MemoryCard card() {
        return new KnowledgeCard("게이트웨이 분리", "토폴로지 분리는 끝났다", List.of("kafka"), List.of(), "");
    }

    @Test
    @DisplayName("승인 색인: 유형 표현이 낸 kind 그대로 벡터를 넣고, BM25 텍스트도 함께 갱신한다")
    void indexApprovedWritesBothChannels() {
        when(representation.embeddingTexts(any())).thenReturn(Map.of("document", "본문"));

        index().indexApproved(7L, MemoryType.KNOWLEDGE, card(), embedding);

        verify(store).saveEmbedding(eq(7L), eq("document"), any());
        verify(store).updateSearchTsv(eq(7L), eq("게이트웨이 분리 토폴로지 분리는 끝났다 kafka"));
    }

    @Test
    @DisplayName("BM25 텍스트는 제목·요약·키워드만 — 카드 전문을 넣지 않는다")
    void keywordTextExcludesBody() {
        when(representation.embeddingTexts(any())).thenReturn(Map.of("document", "본문"));
        MemoryCard withBody =
                new KnowledgeCard("제목", "요약", List.of(), List.of(), "아주 긴 원문 전체가 여기 들어있다");

        index().indexApproved(1L, MemoryType.KNOWLEDGE, withBody, embedding);

        // document(원문)가 tsvector 에 들어가면 그 카드가 이 유형의 거의 모든 질문에서 BM25 상위를 차지한다.
        verify(store).updateSearchTsv(eq(1L), eq("제목 요약"));
    }

    @Test
    @DisplayName("재색인: 벡터만 다시 넣고 BM25 텍스트는 건드리지 않는다")
    void reembedLeavesKeywordTextAlone() {
        when(representation.embeddingTexts(any())).thenReturn(Map.of("document", "본문"));

        index().reembed(9L, MemoryType.KNOWLEDGE, card(), embedding);

        verify(store).saveEmbedding(eq(9L), eq("document"), any());
        // 카드는 그대로이고 임베딩 모델만 바뀐 상황이라, 같은 tsvector 를 다시 쓰는 것은 낭비다.
        verify(store, never()).updateSearchTsv(any(), any());
    }

    @Test
    @DisplayName("표현이 kind 를 여러 개 내면 그만큼 벡터가 저장된다(유형이 정한 이중 벡터)")
    void savesEveryKindTheTypeDeclares() {
        when(representation.embeddingTexts(any()))
                .thenReturn(Map.of("problem", "증상", "solution", "해결"));

        index().reembed(3L, MemoryType.KNOWLEDGE, card(), embedding);

        verify(store).saveEmbedding(eq(3L), eq("problem"), any());
        verify(store).saveEmbedding(eq(3L), eq("solution"), any());
    }
}
