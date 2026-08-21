package com.recall.store;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.MemoryType;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 🔴 S4 유사판정 재조회(load-bearing) — {@code searchStore}가 이미 userId 로 스코프하지만, {@link
 * SimilarMemoryFinder}는 후보를 그대로 믿지 않고 {@code memoryRepository.findByIdAndUserId}로 소유자 조건을 한 번 더
 * 강제한다(이중 방어). 검색 인덱스가 실수로 남의 후보 id를 내더라도(색인 버그·경합 등) 최종 반환은 여전히 소유자 스코프여야 한다.
 *
 * <p>순수 Mockito 유닛테스트로 DB 없이 회귀를 고정한다 — {@code findById}(소유자 무관)로 되돌아가면 이 테스트가 즉시 실패한다.
 */
class SimilarMemoryFinderTest {

    private static final MemoryType TYPE = MemoryType.KNOWLEDGE;
    private static final long CALLER_USER_ID = 1L;
    private static final long CANDIDATE_ID = 99L;

    private static final SearchRepresentation KNOWLEDGE_REP =
            new SearchRepresentation() {
                @Override
                public MemoryType supports() {
                    return MemoryType.KNOWLEDGE;
                }

                @Override
                public Map<String, String> embeddingTexts(Map<String, Object> structured) {
                    return Map.of("document", String.valueOf(structured.get("title")));
                }
            };

    private static UserAiContext embeddingCtx(EmbeddingClient embeddingClient) {
        return new UserAiContext(CALLER_USER_ID, null, embeddingClient, true, true);
    }

    @Test
    @Tag("release-gate")
    @DisplayName("벡터 채널 후보가 남의 memory여도 재조회는 findByIdAndUserId로 걸러낸다(findById 미사용 확인)")
    void vectorCandidateReReadStaysOwnerScoped() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedDocument(anyString())).thenReturn(new float[1024]);

        MemorySearchStore searchStore = mock(MemorySearchStore.class);
        // 검색 인덱스가 (버그로) 남의 소유 후보를 τ_sim 이상 점수로 내놓는 상황을 시뮬레이션.
        when(searchStore.searchByVector(eq(CALLER_USER_ID), any(), eq(TYPE), anyInt()))
                .thenReturn(List.of(new ScoredMemory(CANDIDATE_ID, 0.99)));

        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        // 소유자 스코프 재조회는 비어야 한다(candidate 는 실제로 남의 소유).
        when(memoryRepository.findByIdAndUserId(CANDIDATE_ID, CALLER_USER_ID))
                .thenReturn(Optional.empty());
        // 반면 findById(소유자 무관)로는 존재한다고 stub — findById 를 계속 썼다면 이 테스트가 (거짓으로) 통과했을 것.
        when(memoryRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(mock(Memory.class)));

        SimilarMemoryFinder finder =
                new SimilarMemoryFinder(searchStore, memoryRepository, List.of(KNOWLEDGE_REP));

        Optional<Memory> result =
                finder.findSimilar(
                        CALLER_USER_ID, Map.of("title", "제목"), TYPE, embeddingCtx(embeddingClient));

        assertTrue(result.isEmpty(), "재조회가 소유자 스코프(findByIdAndUserId)라면 후보는 걸러져야 한다");
        verify(memoryRepository).findByIdAndUserId(CANDIDATE_ID, CALLER_USER_ID);
        verify(memoryRepository, never()).findById(anyLong());
    }

    @Test
    @Tag("release-gate")
    @DisplayName("BM25 폴백 후보도 재조회는 findByIdAndUserId로 소유자 유지")
    void keywordFallbackCandidateReReadStaysOwnerScoped() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedDocument(anyString())).thenReturn(new float[1024]);

        MemorySearchStore searchStore = mock(MemorySearchStore.class);
        when(searchStore.searchByVector(eq(CALLER_USER_ID), any(), eq(TYPE), anyInt()))
                .thenReturn(List.of()); // 벡터 채널 미스 → BM25 폴백
        when(searchStore.searchByKeyword(eq(CALLER_USER_ID), anyString(), eq(TYPE), anyInt()))
                .thenReturn(List.of(new ScoredMemory(CANDIDATE_ID, 0.5)));

        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        when(memoryRepository.findByIdAndUserId(CANDIDATE_ID, CALLER_USER_ID))
                .thenReturn(Optional.empty());
        when(memoryRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(mock(Memory.class)));

        SimilarMemoryFinder finder =
                new SimilarMemoryFinder(searchStore, memoryRepository, List.of(KNOWLEDGE_REP));

        Optional<Memory> result =
                finder.findSimilar(
                        CALLER_USER_ID, Map.of("title", "제목"), TYPE, embeddingCtx(embeddingClient));

        assertTrue(result.isEmpty(), "BM25 폴백 재조회도 소유자 스코프를 지켜야 한다");
        verify(memoryRepository).findByIdAndUserId(CANDIDATE_ID, CALLER_USER_ID);
        verify(memoryRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("embedding 미설정(ctx.embeddingReady=false)이면 requireEmbedding()이 막는다")
    void embeddingNotConfiguredBlocks() {
        MemorySearchStore searchStore = mock(MemorySearchStore.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        SimilarMemoryFinder finder =
                new SimilarMemoryFinder(searchStore, memoryRepository, List.of(KNOWLEDGE_REP));

        UserAiContext notReady =
                new UserAiContext(CALLER_USER_ID, null, mock(EmbeddingClient.class), true, false);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.recall.common.AiNotConfiguredException.class,
                () -> finder.findSimilar(CALLER_USER_ID, Map.of("title", "제목"), TYPE, notReady));
    }
}
