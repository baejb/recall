package com.recall.settings;

import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.ProviderCatalog.Role;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전역 모델 설정의 조회·변경. 키는 복호화해 클라이언트에 넘기고, 저장 시 암호화한다(없으면 env 폴백). */
@Service
public class SettingsService {

    private final ModelSettingRepository repository;
    private final SecretCipher cipher;
    private final EmbeddingProperties envEmbedding;
    private final LlmProperties envChat;
    private final EmbeddingClientFactory embeddingFactory;
    private final ApplicationEventPublisher publisher;

    public SettingsService(
            ModelSettingRepository repository,
            SecretCipher cipher,
            EmbeddingProperties envEmbedding,
            LlmProperties envChat,
            EmbeddingClientFactory embeddingFactory,
            ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.cipher = cipher;
        this.envEmbedding = envEmbedding;
        this.envChat = envChat;
        this.embeddingFactory = embeddingFactory;
        this.publisher = publisher;
    }

    private ModelSetting row() {
        return repository
                .findById(1L)
                .orElseThrow(() -> new IllegalStateException("model_setting 미초기화"));
    }

    @Transactional(readOnly = true)
    public EmbeddingProperties currentEmbedding() {
        return embeddingPropsFrom(row());
    }

    /** 행(row)의 현재 값으로 {@link EmbeddingProperties}를 구성한다(키는 복호화, 없으면 env 폴백). */
    private EmbeddingProperties embeddingPropsFrom(ModelSetting s) {
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
        boolean reindexNeeded = false;

        if (u.chatProvider() != null) {
            ProviderCatalog.requireSupported(Role.CHAT, u.chatProvider());
            s.setChatProvider(u.chatProvider());
        }
        if (u.chatModel() != null) s.setChatModel(u.chatModel());
        if (notBlank(u.chatApiKey())) s.setChatApiKeyEnc(encrypt(u.chatApiKey()));

        if (u.embeddingProvider() != null) {
            ProviderCatalog.requireSupported(Role.EMBEDDING, u.embeddingProvider());
            reindexNeeded |= !u.embeddingProvider().equals(s.getEmbeddingProvider());
            s.setEmbeddingProvider(u.embeddingProvider());
        }
        if (u.embeddingModel() != null) {
            reindexNeeded |= !u.embeddingModel().equals(s.getEmbeddingModel());
            s.setEmbeddingModel(u.embeddingModel());
        }
        boolean embeddingKeyRotated = notBlank(u.embeddingApiKey());
        if (embeddingKeyRotated) s.setEmbeddingApiKeyEnc(encrypt(u.embeddingApiKey()));

        // 검증(probe)과 재색인은 별개다: provider/model 이 바뀌면 벡터 공간 자체가 달라져 재색인이
        // 필요하지만, 키만 회전(같은 provider+model)해도 오·타이핑된 키가 그대로 저장돼 나중에야
        // 호출 시점에 실패하지 않도록 새 키로 프로브는 반드시 돌려야 한다(기존 벡터는 유효하게 남음).
        boolean validationNeeded = reindexNeeded || embeddingKeyRotated;
        if (validationNeeded) {
            probeEmbedding(embeddingPropsFrom(s));
        }

        if (reindexNeeded) {
            // 프로브 성공 후에만 재색인 트리거. REINDEXING 을 이 트랜잭션에 함께 커밋하고,
            // 재색인은 AFTER_COMMIT 이벤트 수신자(ReindexService)가 배경에서 수행한다(순환 회피).
            setEmbeddingStatus("REINDEXING");
            publisher.publishEvent(new EmbeddingModelChangedEvent());
        }

        return new UpdateResult(reindexNeeded);
    }

    /**
     * 저장 전 프로브(test-before-save) — 후보 임베딩 설정으로 실제 임베딩 1회를 시도해 유효성을 확인한다. 실패 시(잘못된 키/모델 등) {@link
     * EmbeddingProbeException}을 던져 트랜잭션을 롤백시킨다. 키 값은 예외 메시지에 담지 않는다.
     */
    private void probeEmbedding(EmbeddingProperties candidate) {
        try {
            embeddingFactory.forSettings(candidate).embedDocument("probe");
        } catch (EmbeddingProbeException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingProbeException("임베딩 설정 검증 실패(키/모델 확인): " + e.getMessage());
        }
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

    /**
     * @param embeddingChanged 재색인이 트리거됨(REINDEXING 전이 발생) — 임베딩 키만 회전한 경우는 false.
     */
    public record UpdateResult(boolean embeddingChanged) {}
}
