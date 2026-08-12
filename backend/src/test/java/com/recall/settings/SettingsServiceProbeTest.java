package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.google.GoogleEmbeddingProvider;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.SettingsService.SettingsUpdate;
import com.recall.settings.SettingsService.UpdateResult;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SettingsServiceProbeTest {

    /** 키 회전을 저장할 수 있게 활성화된(fail-closed 아닌) cipher — fail-closed 자체는 SettingsServiceTest에서 검증한다. */
    private static SecretCipher enabledCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    private static ProviderCatalog realCatalog() {
        return new ProviderCatalog(
                List.of(new AnthropicChatProvider(), new OpenAiChatProvider()),
                List.of(
                        new OpenAiEmbeddingProvider(),
                        new VoyageEmbeddingProvider(),
                        new GoogleEmbeddingProvider()));
    }

    private SettingsService newService(
            ModelSettingRepository repo,
            EmbeddingClientFactory factory,
            ApplicationEventPublisher publisher)
            throws Exception {
        return new SettingsService(
                repo,
                enabledCipher(),
                new EmbeddingProperties("voyage", "sk-env-key", null, null, 1024),
                new LlmProperties("anthropic", "", null, null, 4096),
                factory,
                realCatalog(),
                publisher);
    }

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
                        factory,
                        realCatalog(),
                        mock(ApplicationEventPublisher.class));

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

    @Test
    void embeddingKeyOnlyChangeProbesButDoesNotReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findById(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        // provider/model 은 그대로(null), 임베딩 키만 회전.
        UpdateResult result =
                svc.update(new SettingsUpdate(null, null, null, null, null, "sk-rotated"));

        // 키만 바뀌어도 새 키가 유효한지 프로브는 반드시 돈다.
        verify(factory).forSettings(any());
        verify(good).embedDocument("probe");

        // provider/model 이 안 바뀌었으니 기존 벡터는 유효 — 재색인은 트리거하지 않는다.
        verify(publisher, never()).publishEvent(any());
        assertFalse(result.embeddingChanged());
        assertEquals("READY", seed.getEmbeddingStatus());
    }

    @Test
    void embeddingProviderChangeProbesAndTriggersReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findById(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        UpdateResult result =
                svc.update(
                        new SettingsUpdate(
                                null, null, null, "openai", "text-embedding-3-small", null));

        verify(factory).forSettings(any());
        verify(good).embedDocument("probe");
        verify(publisher).publishEvent(any(EmbeddingModelChangedEvent.class));
        assertTrue(result.embeddingChanged());
        assertEquals("REINDEXING", seed.getEmbeddingStatus());
    }

    @Test
    void chatOnlyChangeSkipsProbeAndReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findById(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        UpdateResult result =
                svc.update(new SettingsUpdate("openai", "gpt-4.1", null, null, null, null));

        verify(factory, never()).forSettings(any());
        verify(publisher, never()).publishEvent(any());
        assertFalse(result.embeddingChanged());
        assertEquals("READY", seed.getEmbeddingStatus());
    }
}
