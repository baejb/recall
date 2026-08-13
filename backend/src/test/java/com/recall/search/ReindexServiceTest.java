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
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.type.SearchRepresentation;
import com.recall.settings.ModelSettingRepository;
import com.recall.settings.SettingsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReindexServiceTest {

    private final MemoryRepository memoryRepository = mock(MemoryRepository.class);
    private final MemorySearchStore searchStore = mock(MemorySearchStore.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final EmbeddingClientFactory embeddingClientFactory =
            mock(EmbeddingClientFactory.class);
    private final EmbeddingClient pinnedClient = mock(EmbeddingClient.class);
    private final ModelSettingRepository settingRepository = mock(ModelSettingRepository.class);
    private final SearchRepresentation rep = mock(SearchRepresentation.class);

    private ReindexService newService() {
        return new ReindexService(
                memoryRepository,
                searchStore,
                settingsService,
                embeddingClientFactory,
                List.of(rep),
                settingRepository);
    }

    private Memory activeMemory(long id) {
        Memory m = mock(Memory.class);
        when(m.getId()).thenReturn(id);
        when(m.getType()).thenReturn(MemoryType.KNOWLEDGE);
        when(m.getStructured()).thenReturn("{\"document\":\"t\"}");
        return m;
    }

    /** 잡 시작 시점에 고정(pin)되는 클라이언트를 팩토리가 반환하도록 배선한다. */
    private void wirePinnedClient() {
        EmbeddingProperties props =
                new EmbeddingProperties("voyage", "sk-x", "voyage-3", null, 1024);
        when(settingsService.currentEmbedding()).thenReturn(props);
        when(embeddingClientFactory.forSettings(any())).thenReturn(pinnedClient);
        when(pinnedClient.embedDocument(anyString())).thenReturn(new float[1024]);
    }

    @Test
    @DisplayName("각 활성 memory 를 고정 클라이언트로 재임베딩하고 세대가 현재면 상태를 READY 로 전이한다")
    void reindexAllSuccessSetsReadyWhenGenerationCurrent() {
        Memory m1 = activeMemory(1L);
        Memory m2 = activeMemory(2L);
        when(rep.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(rep.embeddingTexts(any())).thenReturn(Map.of("document", "t"));
        wirePinnedClient();
        when(memoryRepository.findByStatusOrderByCreatedAtDesc("active"))
                .thenReturn(List.of(m1, m2));
        when(settingRepository.updateEmbeddingStatusIfGeneration("READY", 7L)).thenReturn(1);

        newService().reindexAll(7L);

        // 잡 시작 시점의 설정으로 클라이언트를 한 번 고정해 모든 문서에 그 클라이언트를 쓴다.
        verify(embeddingClientFactory).forSettings(any());
        verify(pinnedClient, times(2)).embedDocument(anyString());
        // memory 2건 × kind 1개 = saveEmbedding 2회
        verify(searchStore, times(2)).saveEmbedding(any(), anyString(), any());
        verify(searchStore).saveEmbedding(eq(1L), eq("document"), any());
        verify(searchStore).saveEmbedding(eq(2L), eq("document"), any());
        verify(settingRepository).updateEmbeddingStatusIfGeneration("READY", 7L);
    }

    @Test
    @DisplayName("더 새로운 세대가 행에 반영돼 있으면(대체됨) 뒤처진 잡은 예외 없이 건너뛴다(로그만 남김)")
    void reindexAllDoesNotFailWhenSupersededByNewerGeneration() {
        Memory m1 = activeMemory(1L);
        when(rep.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(rep.embeddingTexts(any())).thenReturn(Map.of("document", "t"));
        wirePinnedClient();
        when(memoryRepository.findByStatusOrderByCreatedAtDesc("active")).thenReturn(List.of(m1));
        // 이 잡의 세대는 5 지만 행은 이미 다른 세대로 대체됨 — UPDATE 가 0건 매치.
        when(settingRepository.updateEmbeddingStatusIfGeneration("READY", 5L)).thenReturn(0);

        newService().reindexAll(5L);

        verify(settingRepository).updateEmbeddingStatusIfGeneration("READY", 5L);
    }

    @Test
    @DisplayName("재임베딩 중 예외가 나면 세대가 현재일 때만 상태를 FAILED 로 전이한다(조용한 실패 금지)")
    void reindexAllFailureSetsFailedWhenGenerationCurrent() {
        Memory m1 = activeMemory(1L);
        when(rep.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(rep.embeddingTexts(any())).thenReturn(Map.of("document", "t"));
        EmbeddingProperties props =
                new EmbeddingProperties("voyage", "sk-x", "voyage-3", null, 1024);
        when(settingsService.currentEmbedding()).thenReturn(props);
        when(embeddingClientFactory.forSettings(any())).thenReturn(pinnedClient);
        when(pinnedClient.embedDocument(anyString())).thenThrow(new RuntimeException("boom"));
        when(memoryRepository.findByStatusOrderByCreatedAtDesc("active")).thenReturn(List.of(m1));
        when(settingRepository.updateEmbeddingStatusIfGeneration("FAILED", 3L)).thenReturn(1);

        newService().reindexAll(3L);

        verify(settingRepository).updateEmbeddingStatusIfGeneration("FAILED", 3L);
    }

    @Test
    @DisplayName("실패해도 더 새로운 세대가 있으면 뒤처진 잡은 예외 없이 건너뛴다(로그만 남김)")
    void reindexAllDoesNotFailWhenSupersededOnFailurePath() {
        Memory m1 = activeMemory(1L);
        when(rep.supports()).thenReturn(MemoryType.KNOWLEDGE);
        when(rep.embeddingTexts(any())).thenReturn(Map.of("document", "t"));
        EmbeddingProperties props =
                new EmbeddingProperties("voyage", "sk-x", "voyage-3", null, 1024);
        when(settingsService.currentEmbedding()).thenReturn(props);
        when(embeddingClientFactory.forSettings(any())).thenReturn(pinnedClient);
        when(pinnedClient.embedDocument(anyString())).thenThrow(new RuntimeException("boom"));
        when(memoryRepository.findByStatusOrderByCreatedAtDesc("active")).thenReturn(List.of(m1));
        when(settingRepository.updateEmbeddingStatusIfGeneration("FAILED", 4L)).thenReturn(0);

        newService().reindexAll(4L);

        verify(settingRepository).updateEmbeddingStatusIfGeneration("FAILED", 4L);
    }
}
