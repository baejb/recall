package com.recall.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.UpdateTimestamp;

/** model_setting 단일 행(id=1). 키 컬럼은 암호문만 담는다. */
@Entity
@Table(name = "model_setting")
public class ModelSetting {

    @Id private Long id;

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

    public Long getId() {
        return id;
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
