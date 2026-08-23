package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.common.config.CurrentUserProvider;
import com.recall.common.exception.ValidationException;
import com.recall.common.secret.SecretCipher;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.google.GoogleChatProvider;
import com.recall.llm.provider.google.GoogleEmbeddingProvider;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.SettingsService.SettingsUpdate;
import com.recall.settings.repository.ModelSettingRepository;
import com.recall.settings.service.ProviderCatalog;
import com.recall.settings.service.entity.ModelSetting;
import com.recall.settings.service.entity.ModelSettingFixture;
import java.util.Base64;
import java.util.List;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SettingsServiceTest {

    /** 테스트는 전부 부트스트랩 사용자(1) 스코프 — 기존(단일 사용자) 동작을 그대로 검증한다. */
    private static final CurrentUserProvider BOOTSTRAP_USER = () -> 1L;

    private static SecretCipher realCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    /** 실제 서술자로 구성한 카탈로그 — capability 검증(embedding≠anthropic 등)이 등록 서술자에서 파생된다. */
    private static ProviderCatalog realCatalog() {
        return new ProviderCatalog(
                List.of(
                        new AnthropicChatProvider(),
                        new OpenAiChatProvider(),
                        new GoogleChatProvider()),
                List.of(
                        new OpenAiEmbeddingProvider(),
                        new VoyageEmbeddingProvider(),
                        new GoogleEmbeddingProvider()));
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
        when(repo.findByUserId(1L)).thenReturn(java.util.Optional.of(seed));
        SettingsService svc =
                new SettingsService(
                        repo,
                        realCipher(),
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        mock(EmbeddingClientFactory.class),
                        realCatalog(),
                        mock(ApplicationEventPublisher.class),
                        BOOTSTRAP_USER);
        assertThrows(
                ValidationException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null,
                                        null,
                                        null,
                                        null,
                                        "anthropic",
                                        "x",
                                        "k",
                                        null))); // 임베딩=anthropic 불가
    }

    @Test
    void failClosedWhenCipherDisabledAndKeyGiven() {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(java.util.Optional.of(seed));
        SettingsService svc =
                new SettingsService(
                        repo,
                        new SecretCipher(""), // 비활성
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        mock(EmbeddingClientFactory.class),
                        realCatalog(),
                        mock(ApplicationEventPublisher.class),
                        BOOTSTRAP_USER);
        assertThrows(
                IllegalStateException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null, null, "sk-x", null, null, null, null, null)));
    }

    @Test
    void currentChatUsesDbBaseUrlWhenSetElseEnv() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting row = ModelSettingFixture.empty();
        row.setChatProvider("anthropic");
        row.setChatModel("claude-opus-4-8");
        row.setEmbeddingProvider("voyage");
        row.setEmbeddingStatus("READY");
        when(repo.findByUserId(1L)).thenReturn(java.util.Optional.of(row));
        SettingsService svc =
                new SettingsService(
                        repo,
                        realCipher(),
                        new EmbeddingProperties("voyage", "", null, "https://env-emb", 1024),
                        new LlmProperties("anthropic", "", null, "https://env-chat", 4096),
                        mock(EmbeddingClientFactory.class),
                        realCatalog(),
                        mock(ApplicationEventPublisher.class),
                        BOOTSTRAP_USER);

        // DB 값 없음 → env 폴백
        assertEquals("https://env-chat", svc.currentChat().baseUrl());
        assertEquals("https://env-emb", svc.currentEmbedding().baseUrl());

        // DB 값 설정 → DB 우선
        row.setChatBaseUrl("https://db-chat");
        row.setEmbeddingBaseUrl("https://db-emb");
        assertEquals("https://db-chat", svc.currentChat().baseUrl());
        assertEquals("https://db-emb", svc.currentEmbedding().baseUrl());
    }

    @Test
    void updatePersistsChatBaseUrlAndClearsWithBlank() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting row = ModelSettingFixture.empty();
        row.setChatProvider("anthropic");
        row.setChatModel("claude-opus-4-8");
        row.setEmbeddingProvider("voyage");
        row.setEmbeddingStatus("READY");
        when(repo.findByUserId(1L)).thenReturn(java.util.Optional.of(row));
        SettingsService svc =
                new SettingsService(
                        repo,
                        realCipher(),
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        mock(EmbeddingClientFactory.class),
                        realCatalog(),
                        mock(ApplicationEventPublisher.class),
                        BOOTSTRAP_USER);

        svc.update(new SettingsUpdate(null, null, null, "https://db-chat", null, null, null, null));
        assertEquals("https://db-chat", row.getChatBaseUrl());

        // 빈 문자열 = 해제(null)
        svc.update(new SettingsUpdate(null, null, null, "", null, null, null, null));
        assertNull(row.getChatBaseUrl());
    }
}
