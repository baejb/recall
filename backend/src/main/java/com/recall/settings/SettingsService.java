package com.recall.settings;

import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.ProviderCatalog.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전역 모델 설정의 조회·변경. 키는 복호화해 클라이언트에 넘기고, 저장 시 암호화한다(없으면 env 폴백). */
@Service
public class SettingsService {

    private final ModelSettingRepository repository;
    private final SecretCipher cipher;
    private final EmbeddingProperties envEmbedding;
    private final LlmProperties envChat;

    public SettingsService(
            ModelSettingRepository repository,
            SecretCipher cipher,
            EmbeddingProperties envEmbedding,
            LlmProperties envChat) {
        this.repository = repository;
        this.cipher = cipher;
        this.envEmbedding = envEmbedding;
        this.envChat = envChat;
    }

    private ModelSetting row() {
        return repository
                .findById(1L)
                .orElseThrow(() -> new IllegalStateException("model_setting 미초기화"));
    }

    @Transactional(readOnly = true)
    public EmbeddingProperties currentEmbedding() {
        ModelSetting s = row();
        String key = decryptOr(s.getEmbeddingApiKeyEnc(), envEmbedding.apiKey());
        return new EmbeddingProperties(
                s.getEmbeddingProvider(), key, s.getEmbeddingModel(), null, 1024);
    }

    @Transactional(readOnly = true)
    public LlmProperties currentChat() {
        ModelSetting s = row();
        String key = decryptOr(s.getChatApiKeyEnc(), envChat.apiKey());
        return new LlmProperties(
                s.getChatProvider(), key, s.getChatModel(), null, envChat.maxTokens());
    }

    @Transactional(readOnly = true)
    public String embeddingStatus() {
        return row().getEmbeddingStatus();
    }

    @Transactional
    public void setEmbeddingStatus(String status) {
        row().setEmbeddingStatus(status);
    }

    @Transactional
    public UpdateResult update(SettingsUpdate u) {
        ModelSetting s = row();
        boolean embeddingChanged = false;

        if (u.chatProvider() != null) {
            ProviderCatalog.requireSupported(Role.CHAT, u.chatProvider());
            s.setChatProvider(u.chatProvider());
        }
        if (u.chatModel() != null) s.setChatModel(u.chatModel());
        if (notBlank(u.chatApiKey())) s.setChatApiKeyEnc(encrypt(u.chatApiKey()));

        if (u.embeddingProvider() != null) {
            ProviderCatalog.requireSupported(Role.EMBEDDING, u.embeddingProvider());
            embeddingChanged |= !u.embeddingProvider().equals(s.getEmbeddingProvider());
            s.setEmbeddingProvider(u.embeddingProvider());
        }
        if (u.embeddingModel() != null) {
            embeddingChanged |= !u.embeddingModel().equals(s.getEmbeddingModel());
            s.setEmbeddingModel(u.embeddingModel());
        }
        if (notBlank(u.embeddingApiKey())) s.setEmbeddingApiKeyEnc(encrypt(u.embeddingApiKey()));

        return new UpdateResult(embeddingChanged);
    }

    private String encrypt(String plaintext) {
        if (!cipher.isEnabled()) {
            throw new IllegalStateException(
                    "RECALL_SECRET_KEY 미설정 — UI 입력 키를 저장할 수 없다(fail-closed)");
        }
        return cipher.encrypt(plaintext);
    }

    private String decryptOr(String enc, String envFallback) {
        if (enc == null || enc.isBlank()) return envFallback;
        return cipher.decrypt(enc);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    public record SettingsUpdate(
            String chatProvider,
            String chatModel,
            String chatApiKey,
            String embeddingProvider,
            String embeddingModel,
            String embeddingApiKey) {}

    public record UpdateResult(boolean embeddingChanged) {}
}
