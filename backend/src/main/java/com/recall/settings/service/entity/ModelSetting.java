package com.recall.settings.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.UpdateTimestamp;

/** 사용자별 model_setting 행(user_id 당 1행, {@code uq_model_setting_user}). 키 컬럼은 암호문만 담는다. */
@Entity
@Table(name = "model_setting")
public class ModelSetting {

    /** V4 기본값(신규 행 생성 시 사용) — JPA insert 는 DB DEFAULT 를 타지 않으므로 자바에서 동일 값을 명시한다. */
    private static final String DEFAULT_CHAT_PROVIDER = "anthropic";

    private static final String DEFAULT_CHAT_MODEL = "claude-opus-4-8";
    private static final String DEFAULT_EMBEDDING_PROVIDER = "voyage";
    private static final String DEFAULT_EMBEDDING_STATUS = "READY";

    // id 는 V13 에서 identity 로 전환됐다 — 신규 행은 DB 가 발번한다(기존 시드 id=1 은 유지).
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "chat_provider", nullable = false)
    private String chatProvider;

    @Column(name = "chat_model", nullable = false)
    private String chatModel;

    @Column(name = "chat_api_key_enc")
    private String chatApiKeyEnc;

    @Column(name = "chat_base_url")
    private String chatBaseUrl;

    @Column(name = "embedding_provider", nullable = false)
    private String embeddingProvider;

    @Column(name = "embedding_model")
    private String embeddingModel;

    @Column(name = "embedding_api_key_enc")
    private String embeddingApiKeyEnc;

    @Column(name = "embedding_base_url")
    private String embeddingBaseUrl;

    @Column(name = "embedding_status", nullable = false)
    private String embeddingStatus;

    @Column(name = "embedding_generation", nullable = false)
    private long embeddingGeneration;

    @Column(name = "configured", nullable = false)
    private boolean configured;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ModelSetting() {}

    /**
     * 신규 사용자용 기본 행(아직 미저장). NOT NULL 컬럼을 V4 기본값과 동일하게 채운다 — JPA insert 는 DB DEFAULT 를 타지 않으므로 여기서
     * 명시하지 않으면 NOT NULL 위반이 난다. 키 컬럼은 비운다(미설정). id 는 identity 로 발번된다.
     */
    public static ModelSetting forUser(long userId) {
        ModelSetting s = new ModelSetting();
        s.userId = userId;
        s.chatProvider = DEFAULT_CHAT_PROVIDER;
        s.chatModel = DEFAULT_CHAT_MODEL;
        s.embeddingProvider = DEFAULT_EMBEDDING_PROVIDER;
        s.embeddingStatus = DEFAULT_EMBEDDING_STATUS;
        s.embeddingGeneration = 0L;
        s.configured = false;
        return s;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getChatProvider() {
        return chatProvider;
    }

    public void setChatProvider(String v) {
        this.chatProvider = v;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String v) {
        this.chatModel = v;
    }

    public String getChatApiKeyEnc() {
        return chatApiKeyEnc;
    }

    public void setChatApiKeyEnc(String v) {
        this.chatApiKeyEnc = v;
    }

    public String getChatBaseUrl() {
        return chatBaseUrl;
    }

    public void setChatBaseUrl(String v) {
        this.chatBaseUrl = v;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String v) {
        this.embeddingProvider = v;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String v) {
        this.embeddingModel = v;
    }

    public String getEmbeddingApiKeyEnc() {
        return embeddingApiKeyEnc;
    }

    public void setEmbeddingApiKeyEnc(String v) {
        this.embeddingApiKeyEnc = v;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String v) {
        this.embeddingBaseUrl = v;
    }

    public String getEmbeddingStatus() {
        return embeddingStatus;
    }

    public void setEmbeddingStatus(String v) {
        this.embeddingStatus = v;
    }

    public long getEmbeddingGeneration() {
        return embeddingGeneration;
    }

    public void setEmbeddingGeneration(long v) {
        this.embeddingGeneration = v;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean v) {
        this.configured = v;
    }
}
