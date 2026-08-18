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
import com.recall.llm.UserAiContext;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.type.PlanContribution;
import com.recall.settings.SettingsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 벡터 채널이 격하(BM25 전용)되는 두 축을 확인한다(불변 원칙: 조용한 실패 금지 — 상태를 보고 동작을 바꾼다): (1) {@code
 * ctx.embeddingReady()==false}(embedding 미설정 — 차단이 아니라 격하), (2) embedding_status 가 READY가
 * 아님(REINDEXING· FAILED — 재색인 중 신구 모델 벡터 혼재).
 */
class HybridSearchDegradeTest {

    private final MemorySearchStore store = mock(MemorySearchStore.class);
    private final MemoryRepository memoryRepository = mock(MemoryRepository.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final PlanContribution plan = mock(PlanContribution.class);

    private HybridSearchService newService() {
        when(plan.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(plan.channelWeights()).thenReturn(Map.of("memory_vector", 1.0, "memory_bm25", 1.0));
        return new HybridSearchService(store, memoryRepository, List.of(plan), settings);
    }

    private static UserAiContext ctx(boolean embeddingReady) {
        return new UserAiContext(1L, null, mock(EmbeddingClient.class), true, embeddingReady);
    }

    @Test
    @DisplayName("embedding 미설정(embeddingReady=false)이면 벡터 채널을 건너뛰고 BM25만 조회한다(차단 아님, 격하)")
    void skipsVectorChannelWhenEmbeddingNotConfigured() {
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        newService().search("q", MemoryType.KNOWLEDGE, ctx(false));

        verify(store, never()).searchByVector(anyLong(), any(), any(), anyInt());
        verify(settings, never()).embeddingStatus(anyLong()); // embeddingReady에서 이미 격하 — 상태 조회도 안 함
        verify(store).searchByKeyword(eq(1L), eq("q"), eq(MemoryType.KNOWLEDGE), anyInt());
    }

    @Test
    @DisplayName("REINDEXING 중엔 embedding이 설정돼 있어도 벡터 채널을 건너뛰고 BM25만 조회한다")
    void skipsVectorChannelWhileReindexing() {
        when(settings.embeddingStatus(1L)).thenReturn("REINDEXING");
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        newService().search("q", MemoryType.KNOWLEDGE, ctx(true));

        verify(store, never()).searchByVector(anyLong(), any(), any(), anyInt());
        verify(store).searchByKeyword(eq(1L), eq("q"), eq(MemoryType.KNOWLEDGE), anyInt());
    }

    @Test
    @DisplayName("FAILED 중엔 신구 모델 벡터 혼재라 벡터 채널을 건너뛰고 BM25만 조회한다")
    void skipsVectorChannelWhileFailed() {
        when(settings.embeddingStatus(1L)).thenReturn("FAILED");
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        newService().search("q", MemoryType.KNOWLEDGE, ctx(true));

        verify(store, never()).searchByVector(anyLong(), any(), any(), anyInt());
        verify(store).searchByKeyword(eq(1L), eq("q"), eq(MemoryType.KNOWLEDGE), anyInt());
    }

    @Test
    @DisplayName("embedding 설정됨 + READY 상태에선 평소처럼 벡터 채널도 조회한다")
    void queriesVectorChannelWhenReady() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        UserAiContext ready = new UserAiContext(1L, null, embedding, true, true);
        when(settings.embeddingStatus(1L)).thenReturn("READY");
        when(embedding.embedQuery(any())).thenReturn(new float[1024]);
        when(store.searchByVector(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        newService().search("q", MemoryType.KNOWLEDGE, ready);

        verify(store).searchByVector(eq(1L), any(), eq(MemoryType.KNOWLEDGE), anyInt());
    }

    @Test
    @DisplayName("READY인데 외부 임베딩 호출이 일시 실패하면 벡터 채널만 격하하고 BM25로 응답한다(질의 전체 실패 아님)")
    void degradesVectorChannelOnEmbeddingFailure() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        UserAiContext ready = new UserAiContext(1L, null, embedding, true, true);
        when(settings.embeddingStatus(1L)).thenReturn("READY");
        when(embedding.embedQuery(any())).thenThrow(new RuntimeException("임베딩 API 일시 실패(외부 장애)"));
        when(store.searchByKeyword(anyLong(), any(), any(), anyInt())).thenReturn(List.of());

        // 설정은 됐으나 외부 호출이 실패 — 예외가 위로 전파되지 않고 BM25만으로 정상 반환(설계 §4 격하).
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> newService().search("q", MemoryType.KNOWLEDGE, ready));
        verify(store).searchByKeyword(eq(1L), eq("q"), eq(MemoryType.KNOWLEDGE), anyInt());
    }
}
