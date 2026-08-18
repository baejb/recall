package com.recall.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.type.PlanContribution;
import com.recall.settings.SettingsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * READY 가 아닌 임베딩 상태(REINDEXING·FAILED)에서 벡터 채널을 격하(BM25 전용)하는지 확인한다(불변 원칙: 조용한 실패 금지 — 상태를 보고 동작을
 * 바꾼다).
 */
class HybridSearchDegradeTest {

    private final MemorySearchStore store = mock(MemorySearchStore.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final MemoryRepository memoryRepository = mock(MemoryRepository.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final PlanContribution plan = mock(PlanContribution.class);

    private HybridSearchService newService() {
        when(plan.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(plan.channelWeights()).thenReturn(Map.of("memory_vector", 1.0, "memory_bm25", 1.0));
        return new HybridSearchService(
                store, embeddingClient, memoryRepository, List.of(plan), settings);
    }

    @Test
    @DisplayName("REINDEXING 중엔 벡터 채널을 건너뛰고 BM25만 조회한다")
    void skipsVectorChannelWhileReindexing() {
        when(settings.embeddingStatus()).thenReturn("REINDEXING");
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        newService().search(1L, "q", MemoryType.KNOWLEDGE);

        verify(store, never()).searchByVector(anyLong(), any(), any(), anyInt());
        verify(embeddingClient, never()).embedQuery(any());
        verify(store).searchByKeyword(eq(1L), eq("q"), eq(MemoryType.KNOWLEDGE), anyInt());
    }

    @Test
    @DisplayName("FAILED 중엔 신구 모델 벡터 혼재라 벡터 채널을 건너뛰고 BM25만 조회한다")
    void skipsVectorChannelWhileFailed() {
        when(settings.embeddingStatus()).thenReturn("FAILED");
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        newService().search(1L, "q", MemoryType.KNOWLEDGE);

        verify(store, never()).searchByVector(anyLong(), any(), any(), anyInt());
        verify(embeddingClient, never()).embedQuery(any());
        verify(store).searchByKeyword(eq(1L), eq("q"), eq(MemoryType.KNOWLEDGE), anyInt());
    }

    @Test
    @DisplayName("READY 상태에선 평소처럼 벡터 채널도 조회한다")
    void queriesVectorChannelWhenReady() {
        when(settings.embeddingStatus()).thenReturn("READY");
        when(embeddingClient.embedQuery(any())).thenReturn(new float[1024]);
        when(store.searchByVector(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        newService().search(1L, "q", MemoryType.KNOWLEDGE);

        verify(store).searchByVector(eq(1L), any(), eq(MemoryType.KNOWLEDGE), anyInt());
    }
}
