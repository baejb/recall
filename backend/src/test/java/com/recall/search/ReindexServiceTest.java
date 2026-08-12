package com.recall.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.type.SearchRepresentation;
import com.recall.settings.ModelSetting;
import com.recall.settings.ModelSettingRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReindexServiceTest {

    private final MemoryRepository memoryRepository = mock(MemoryRepository.class);
    private final MemorySearchStore searchStore = mock(MemorySearchStore.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final ModelSettingRepository settingRepository = mock(ModelSettingRepository.class);
    private final SearchRepresentation rep = mock(SearchRepresentation.class);

    private ReindexService newService() {
        return new ReindexService(
                memoryRepository, searchStore, embeddingClient, List.of(rep), settingRepository);
    }

    private Memory activeMemory(long id) {
        Memory m = mock(Memory.class);
        when(m.getId()).thenReturn(id);
        when(m.getType()).thenReturn(MemoryType.KNOWLEDGE);
        when(m.getStructured()).thenReturn("{\"document\":\"t\"}");
        return m;
    }

    @Test
    @DisplayName("각 활성 memory 를 재임베딩하고 성공 시 상태를 READY 로 전이한다")
    void reindexAllSuccessSetsReady() {
        Memory m1 = activeMemory(1L);
        Memory m2 = activeMemory(2L);
        when(rep.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(rep.embeddingTexts(any())).thenReturn(Map.of("document", "t"));
        when(embeddingClient.embedDocument(anyString())).thenReturn(new float[1024]);
        when(memoryRepository.findByStatusOrderByCreatedAtDesc("active"))
                .thenReturn(List.of(m1, m2));
        ModelSetting setting = mock(ModelSetting.class);
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));

        newService().reindexAll();

        // memory 2건 × kind 1개 = saveEmbedding 2회
        verify(searchStore, times(2)).saveEmbedding(any(), anyString(), any());
        verify(searchStore).saveEmbedding(eq(1L), eq("document"), any());
        verify(searchStore).saveEmbedding(eq(2L), eq("document"), any());
        verify(setting).setEmbeddingStatus("READY");
    }

    @Test
    @DisplayName("재임베딩 중 예외가 나면 상태를 FAILED 로 전이한다(조용한 실패 금지)")
    void reindexAllFailureSetsFailed() {
        Memory m1 = activeMemory(1L);
        when(rep.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(rep.embeddingTexts(any())).thenReturn(Map.of("document", "t"));
        when(embeddingClient.embedDocument(anyString())).thenThrow(new RuntimeException("boom"));
        when(memoryRepository.findByStatusOrderByCreatedAtDesc("active")).thenReturn(List.of(m1));
        ModelSetting setting = mock(ModelSetting.class);
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));

        newService().reindexAll();

        verify(setting).setEmbeddingStatus("FAILED");
    }
}
