package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.SettingsService.SettingsUpdate;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SettingsServiceTest {

    private static SecretCipher realCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    private ModelSetting seedRow() {
        ModelSetting s = mock(ModelSetting.class);
        when(s.getChatProvider()).thenReturn("anthropic");
        when(s.getChatModel()).thenReturn("claude-opus-4-8");
        when(s.getEmbeddingProvider()).thenReturn("voyage");
        when(s.getEmbeddingStatus()).thenReturn("READY");
        return s;
    }

    @Test
    void updateRejectsUnsupportedEmbeddingProvider() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(seed));
        SettingsService svc =
                new SettingsService(
                        repo,
                        realCipher(),
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        mock(EmbeddingClientFactory.class),
                        mock(ApplicationEventPublisher.class));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null,
                                        null,
                                        null,
                                        "anthropic",
                                        "x",
                                        "k"))); // 임베딩=anthropic 불가
    }

    @Test
    void failClosedWhenCipherDisabledAndKeyGiven() {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(seed));
        SettingsService svc =
                new SettingsService(
                        repo,
                        new SecretCipher(""), // 비활성
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        mock(EmbeddingClientFactory.class),
                        mock(ApplicationEventPublisher.class));
        assertThrows(
                IllegalStateException.class,
                () -> svc.update(new SettingsUpdate(null, null, "sk-x", null, null, null)));
    }
}
