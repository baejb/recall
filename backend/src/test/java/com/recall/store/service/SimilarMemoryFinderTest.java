package com.recall.store.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.type.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.MemoryAccess;
import com.recall.memory.StoredMemory;
import com.recall.memory.type.EmbeddingKind;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SearchRepresentation;
import com.recall.memory.type.knowledge.KnowledgeCard;
import com.recall.memory.type.troubleshooting.TroubleshootingCard;
import com.recall.search.ScoredMemory;
import com.recall.search.SearchIndex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 🔴 S4 유사판정 재조회(load-bearing) — {@code searchIndex}가 이미 userId 로 스코프하지만, {@link
 * SimilarMemoryFinder}는 후보를 그대로 믿지 않고 {@code MemoryAccess.findOwned}로 소유자 조건을 한 번 더 강제한다(이중 방어). 검색
 * 인덱스가 실수로 남의 후보 id를 내더라도(색인 버그·경합 등) 최종 반환은 여전히 소유자 스코프여야 한다.
 *
 * <p>순수 Mockito 유닛테스트로 DB 없이 회귀를 고정한다. 예전에는 "소유자 무관 조회({@code findById})로 되돌아가지 않는지"까지 감시했지만, 지금은
 * {@code MemoryAccess} 계약에 그런 메서드가 없어 <b>쓸 수가 없다</b> — 그 가드는 테스트에서 타입으로 올라갔다.
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
                public Map<String, String> embeddingTexts(MemoryCard card) {
                    return Map.of(EmbeddingKind.DOCUMENT, card.title());
                }
            };

    /** document kind가 없는 유형(트러블슈팅=problem·solution 이중 벡터)의 검색 표현. kind 순서가 대표 텍스트를 정한다. */
    private static final SearchRepresentation TROUBLESHOOTING_REP =
            new SearchRepresentation() {
                @Override
                public MemoryType supports() {
                    return MemoryType.TROUBLESHOOTING;
                }

                @Override
                public Map<String, String> embeddingTexts(MemoryCard card) {
                    TroubleshootingCard ts = (TroubleshootingCard) card;
                    Map<String, String> texts = new LinkedHashMap<>();
                    texts.put(EmbeddingKind.PROBLEM, ts.symptom());
                    texts.put(EmbeddingKind.SOLUTION, ts.finalSolution());
                    return texts;
                }
            };

    /** 테스트용 knowledge 카드(제목만). */
    private static KnowledgeCard knCard(String title) {
        return new KnowledgeCard(title, "", List.of(), List.of(), "");
    }

    /** 테스트용 troubleshooting 카드(증상·해결). */
    private static TroubleshootingCard tsCard(String title, String symptom, String solution) {
        return new TroubleshootingCard(
                title, "", List.of(), symptom, "", "", "", List.of(), "", solution, null);
    }

    private static UserAiContext embeddingCtx(EmbeddingClient embeddingClient) {
        return new UserAiContext(CALLER_USER_ID, null, embeddingClient, true, true);
    }

    @Test
    @DisplayName("document kind가 없는 유형은 첫 kind(problem)를 대표 텍스트로 쓴다 — title 폴백으로 떨어지지 않는다")
    void usesFirstKindWhenTypeHasNoDocument() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedDocument(anyString())).thenReturn(new float[1024]);

        SearchIndex searchIndex = mock(SearchIndex.class);
        // kind 를 eq(EmbeddingKind.PROBLEM) 으로 고정한다 — 대표 kind 를 SQL 까지 넘기지 않으면(모든 kind 중 MAX) 신규
        // 증상이 기존 카드의 solution 벡터와도 대조돼, 판정 프롬프트가 설계하지 않은 짝이 S4 로 넘어간다.
        when(searchIndex.topByVector(
                        eq(CALLER_USER_ID),
                        any(),
                        eq(MemoryType.TROUBLESHOOTING),
                        eq(EmbeddingKind.PROBLEM),
                        anyInt()))
                .thenReturn(List.of());
        when(searchIndex.topByKeyword(
                        eq(CALLER_USER_ID), anyString(), eq(MemoryType.TROUBLESHOOTING), anyInt()))
                .thenReturn(List.of());

        SimilarMemoryFinder finder =
                new SimilarMemoryFinder(
                        searchIndex,
                        mock(MemoryAccess.class),
                        List.of(KNOWLEDGE_REP, TROUBLESHOOTING_REP));

        finder.findSimilar(
                CALLER_USER_ID,
                tsCard("제목", "컨테이너가 죽는다", "한도 상향"),
                MemoryType.TROUBLESHOOTING,
                embeddingCtx(embeddingClient));

        // 증상(problem)으로 유사 후보를 찾아야 한다 — 같은 문제인지가 판정의 출발점이기 때문.
        verify(embeddingClient).embedDocument("컨테이너가 죽는다");
    }

    @Test
    @Tag("release-gate")
    @DisplayName("벡터 채널 후보가 남의 memory여도 재조회는 소유자 스코프(findOwned)로 걸러낸다")
    void vectorCandidateReReadStaysOwnerScoped() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedDocument(anyString())).thenReturn(new float[1024]);

        SearchIndex searchIndex = mock(SearchIndex.class);
        // 검색 인덱스가 (버그로) 남의 소유 후보를 τ_sim 이상 점수로 내놓는 상황을 시뮬레이션.
        // 지식 유형의 대표 kind 는 document — "문서 vs 문서" 대조라는 규약이 SQL 까지 관통하는지도 함께 고정한다.
        when(searchIndex.topByVector(
                        eq(CALLER_USER_ID), any(), eq(TYPE), eq(EmbeddingKind.DOCUMENT), anyInt()))
                .thenReturn(List.of(new ScoredMemory(CANDIDATE_ID, 0.99)));

        MemoryAccess memories = mock(MemoryAccess.class);
        // 소유자 스코프 재조회는 비어야 한다(candidate 는 실제로 남의 소유).
        // 소유자 스코프가 아닌 조회는 애초에 계약에 없다 — findOwned 만 존재한다.
        when(memories.findOwned(CANDIDATE_ID, CALLER_USER_ID)).thenReturn(Optional.empty());

        SimilarMemoryFinder finder =
                new SimilarMemoryFinder(searchIndex, memories, List.of(KNOWLEDGE_REP));

        Optional<StoredMemory> result =
                finder.findSimilar(
                        CALLER_USER_ID, knCard("제목"), TYPE, embeddingCtx(embeddingClient));

        assertTrue(result.isEmpty(), "재조회가 소유자 스코프(findOwned)라면 후보는 걸러져야 한다");
        verify(memories).findOwned(CANDIDATE_ID, CALLER_USER_ID);
        // findById(소유자 무관) 를 쓰지 않는지 감시하던 verify 는 없앴다 — MemoryAccess 계약에 그런
        // 메서드가 없어서 애초에 쓸 수 없다. 가드가 테스트에서 타입으로 올라갔다.
    }

    @Test
    @Tag("release-gate")
    @DisplayName("BM25 폴백 후보도 재조회는 소유자 스코프(findOwned)를 유지")
    void keywordFallbackCandidateReReadStaysOwnerScoped() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedDocument(anyString())).thenReturn(new float[1024]);

        SearchIndex searchIndex = mock(SearchIndex.class);
        when(searchIndex.topByVector(
                        eq(CALLER_USER_ID), any(), eq(TYPE), eq(EmbeddingKind.DOCUMENT), anyInt()))
                .thenReturn(List.of()); // 벡터 채널 미스 → BM25 폴백
        when(searchIndex.topByKeyword(eq(CALLER_USER_ID), anyString(), eq(TYPE), anyInt()))
                .thenReturn(List.of(new ScoredMemory(CANDIDATE_ID, 0.5)));

        MemoryAccess memories = mock(MemoryAccess.class);
        when(memories.findOwned(CANDIDATE_ID, CALLER_USER_ID)).thenReturn(Optional.empty());

        SimilarMemoryFinder finder =
                new SimilarMemoryFinder(searchIndex, memories, List.of(KNOWLEDGE_REP));

        Optional<StoredMemory> result =
                finder.findSimilar(
                        CALLER_USER_ID, knCard("제목"), TYPE, embeddingCtx(embeddingClient));

        assertTrue(result.isEmpty(), "BM25 폴백 재조회도 소유자 스코프를 지켜야 한다");
        verify(memories).findOwned(CANDIDATE_ID, CALLER_USER_ID);
        // findById(소유자 무관) 를 쓰지 않는지 감시하던 verify 는 없앴다 — MemoryAccess 계약에 그런
        // 메서드가 없어서 애초에 쓸 수 없다. 가드가 테스트에서 타입으로 올라갔다.
    }

    @Test
    @DisplayName("embedding 미설정(ctx.embeddingReady=false)이면 requireEmbedding()이 막는다")
    void embeddingNotConfiguredBlocks() {
        SearchIndex searchIndex = mock(SearchIndex.class);
        MemoryAccess memories = mock(MemoryAccess.class);
        SimilarMemoryFinder finder =
                new SimilarMemoryFinder(searchIndex, memories, List.of(KNOWLEDGE_REP));

        UserAiContext notReady =
                new UserAiContext(CALLER_USER_ID, null, mock(EmbeddingClient.class), true, false);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.recall.common.exception.AiNotConfiguredException.class,
                () -> finder.findSimilar(CALLER_USER_ID, knCard("제목"), TYPE, notReady));
    }
}
