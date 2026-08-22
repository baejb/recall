package com.recall.search.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.exception.AiNotConfiguredException;
import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.AiContextFactory;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.MemoryAccess;
import com.recall.memory.StoredMemory;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.knowledge.KnowledgeCard;
import com.recall.search.SearchIndex;
import com.recall.settings.SettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReindexServiceTest {

    private static final long USER_ID = 42L;

    private final MemoryAccess memories = mock(MemoryAccess.class);
    private final SearchIndex searchIndex = mock(SearchIndex.class);
    private final EmbeddingClient pinnedClient = mock(EmbeddingClient.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final CardCodec cardCodec = mock(CardCodec.class);

    /**
     * {@link StrategyRegistry}는 생성 시점에 등록된 각 전략의 {@code supports()}를 즉시 조회해 키로 쓴다 — 컨텍스트 고정 실패로
     * 전략까지 도달하지 않는 테스트에서도 {@code newService()}(=ReindexService 생성자) 호출 자체는 항상 거치므로, 모든 테스트에서 스텁이
     * 필요하다.
     */
    @BeforeEach
    void stubStrategyKey() {
        // 카드를 읽을 수 있는 정상 상태가 기본이다 — readOrNull 의 mock 기본값(null)은 "못 읽는 카드"를
        // 뜻하고, 그 경우 재색인은 그 memory 의 벡터를 비우고 건너뛴다(전용 테스트에서 확인).
        when(cardCodec.readOrNull(any(), anyString()))
                .thenReturn(new KnowledgeCard("t", "", List.of(), List.of(), "t"));
    }

    private ReindexService newService() {
        return new ReindexService(
                memories, searchIndex, mock(AiContextFactory.class), cardCodec, settings);
    }

    private StoredMemory activeMemory(long id) {
        return new StoredMemory(id, MemoryType.KNOWLEDGE, "{\"document\":\"t\"}");
    }

    /** {@code requireEmbedding()} 이 잡 시작 시점에 고정(pin)되는 클라이언트를 돌려주는 준비된 컨텍스트. */
    private UserAiContext readyContext() {
        UserAiContext ctx = mock(UserAiContext.class);
        when(ctx.requireEmbedding()).thenReturn(pinnedClient);
        return ctx;
    }

    /** {@code requireEmbedding()} 이 즉시 실패하는 컨텍스트(미설정/장애) — 재색인 실패 경로 재현용. */
    private UserAiContext failingContext() {
        UserAiContext ctx = mock(UserAiContext.class);
        when(ctx.requireEmbedding()).thenThrow(new AiNotConfiguredException("embedding 미설정"));
        return ctx;
    }

    @Test
    @DisplayName("각 활성 memory 를 고정 클라이언트로 재임베딩하고 세대가 현재면 상태를 READY 로 전이한다")
    void reindexUserSuccessSetsReadyWhenGenerationCurrent() {
        StoredMemory m1 = activeMemory(1L);
        StoredMemory m2 = activeMemory(2L);
        when(memories.activeOf(USER_ID)).thenReturn(List.of(m1, m2));
        when(settings.setEmbeddingStatusIfGeneration(USER_ID, "READY", 7L)).thenReturn(1);

        newService().reindexUser(USER_ID, 7L, readyContext());

        // 잡 시작 시점에 한 번 고정한 <b>같은</b> 클라이언트가 모든 문서에 넘어가야 한다(모델 혼재 방지).
        // 임베딩 호출 자체는 SearchIndex 안이라, 여기서는 넘긴 인스턴스로 고정을 확인한다.
        verify(searchIndex, times(2)).reembed(anyLong(), any(), any(), eq(pinnedClient));
        verify(searchIndex).reembed(eq(1L), eq(MemoryType.KNOWLEDGE), any(), any());
        verify(searchIndex).reembed(eq(2L), eq(MemoryType.KNOWLEDGE), any(), any());
        verify(settings).setEmbeddingStatusIfGeneration(USER_ID, "READY", 7L);
    }

    @Test
    @DisplayName("🟠 카드를 읽을 수 없는 memory 는 벡터를 비우고 건너뛴다 — 잡 전체가 FAILED 되지 않는다")
    void unreadableCardIsSkippedWithoutFailingTheJob() {
        // 카드 하나가 못 읽히면 전에는 루프가 던져 catch 가 embedding_status=FAILED 로 전이했고,
        // HybridSearchService 는 READY 만 허용하므로 그 사용자의 벡터 채널이 통째로 꺼졌다.
        // 게다가 그 카드는 영원히 못 읽으므로 FAILED 가 고착됐다.
        StoredMemory bad = activeMemory(1L);
        StoredMemory good = activeMemory(2L);
        when(cardCodec.readOrNull(any(), anyString()))
                .thenReturn(null) // 1번: 못 읽는 레거시 카드
                .thenReturn(new KnowledgeCard("t", "", List.of(), List.of(), "t"));
        when(pinnedClient.embedDocument(anyString())).thenReturn(new float[1024]);
        when(memories.activeOf(USER_ID)).thenReturn(List.of(bad, good));
        when(settings.setEmbeddingStatusIfGeneration(USER_ID, "READY", 7L)).thenReturn(1);

        newService().reindexUser(USER_ID, 7L, readyContext());

        // 못 읽은 카드는 낡은 벡터를 남기지 않는다 — 남기면 신구 모델이 섞여 READY 가 거짓이 된다.
        verify(searchIndex).clearEmbeddings(1L);
        verify(searchIndex, never()).reembed(eq(1L), any(), any(), any());
        // 나머지는 정상 재색인되고 잡은 READY 로 끝난다(벡터 채널이 꺼지지 않는다).
        verify(searchIndex).reembed(eq(2L), eq(MemoryType.KNOWLEDGE), any(), any());
        verify(settings).setEmbeddingStatusIfGeneration(USER_ID, "READY", 7L);
        verify(settings, never())
                .setEmbeddingStatusIfGeneration(eq(USER_ID), eq("FAILED"), anyLong());
    }

    @Test
    @DisplayName("더 새로운 세대가 행에 반영돼 있으면(대체됨) 뒤처진 잡은 예외 없이 건너뛴다(로그만 남김)")
    void reindexUserDoesNotFailWhenSupersededByNewerGeneration() {
        StoredMemory m1 = activeMemory(1L);
        when(pinnedClient.embedDocument(anyString())).thenReturn(new float[1024]);
        when(memories.activeOf(USER_ID)).thenReturn(List.of(m1));
        // 이 잡의 세대는 5 지만 행은 이미 다른 세대로 대체됨 — UPDATE 가 0건 매치.
        when(settings.setEmbeddingStatusIfGeneration(USER_ID, "READY", 5L)).thenReturn(0);

        newService().reindexUser(USER_ID, 5L, readyContext());

        verify(settings).setEmbeddingStatusIfGeneration(USER_ID, "READY", 5L);
    }

    @Test
    @DisplayName("재임베딩 중 예외가 나면 세대가 현재일 때만 상태를 FAILED 로 전이한다(조용한 실패 금지)")
    void reindexUserFailureSetsFailedWhenGenerationCurrent() {
        StoredMemory m1 = activeMemory(1L);
        doThrow(new RuntimeException("boom"))
                .when(searchIndex)
                .reembed(anyLong(), any(), any(), any());
        when(memories.activeOf(USER_ID)).thenReturn(List.of(m1));
        when(settings.setEmbeddingStatusIfGeneration(USER_ID, "FAILED", 3L)).thenReturn(1);

        newService().reindexUser(USER_ID, 3L, readyContext());

        verify(settings).setEmbeddingStatusIfGeneration(USER_ID, "FAILED", 3L);
    }

    @Test
    @DisplayName("실패해도 더 새로운 세대가 있으면 뒤처진 잡은 예외 없이 건너뛴다(로그만 남김)")
    void reindexUserDoesNotFailWhenSupersededOnFailurePath() {
        when(settings.setEmbeddingStatusIfGeneration(USER_ID, "FAILED", 4L)).thenReturn(0);

        newService().reindexUser(USER_ID, 4L, failingContext());

        verify(settings).setEmbeddingStatusIfGeneration(USER_ID, "FAILED", 4L);
    }

    @Test
    @DisplayName(
            "embedding 미설정 컨텍스트는 재색인을 FAILED 로 전이한다(requireEmbedding 이 곧장 실패, memory 조회 이전에 차단)")
    void reindexUserFailsFastWhenEmbeddingNotReady() {
        when(settings.setEmbeddingStatusIfGeneration(USER_ID, "FAILED", 1L)).thenReturn(1);

        newService().reindexUser(USER_ID, 1L, failingContext());

        verify(settings).setEmbeddingStatusIfGeneration(USER_ID, "FAILED", 1L);
        // 컨텍스트 고정(requireEmbedding) 자체가 실패했으므로 memory 조회까지 가지 않는다.
        verify(memories, never()).activeOf(anyLong());
    }
}
