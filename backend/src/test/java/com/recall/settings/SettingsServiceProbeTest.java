package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.SettingsService.SettingsUpdate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsServiceProbeTest {

    private ModelSetting seedRow() {
        ModelSetting s = new ModelSetting();
        s.setChatProvider("anthropic");
        s.setChatModel("claude-opus-4-8");
        s.setEmbeddingProvider("voyage");
        s.setEmbeddingModel("voyage-3");
        s.setEmbeddingStatus("READY");
        return s;
    }

    @Test
    void probeFailureRejectsSaveAndNoStatusChange() {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findById(1L)).thenReturn(Optional.of(seed));

        // factory 가 예외 던지는 임베딩 클라이언트를 반환하도록 구성
        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient bad = mock(EmbeddingClient.class);
        when(bad.embedDocument(anyString())).thenThrow(new RuntimeException("401 unauthorized"));
        when(factory.forSettings(any())).thenReturn(bad);

        SettingsService svc =
                new SettingsService(
                        repo,
                        new SecretCipher(""),
                        new EmbeddingProperties("voyage", "sk-env-key", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        factory);

        assertThrows(
                EmbeddingProbeException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null,
                                        null,
                                        null,
                                        "openai",
                                        "text-embedding-3-small",
                                        null))); // 임베딩 provider 변경 → 프로브 실패

        // 프로브가 실제로 호출됐는지 확인
        verify(factory).forSettings(any());
        verify(bad).embedDocument("probe");

        // 프로브 실패로 롤백되므로 상태는 그대로다(별도 경로에서만 상태 전이).
        assertEquals("READY", seed.getEmbeddingStatus());
    }
}
