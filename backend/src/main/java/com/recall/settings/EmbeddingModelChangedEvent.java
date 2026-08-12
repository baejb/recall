package com.recall.settings;

/**
 * 임베딩 provider/model 이 실제로 바뀌었을 때 발행되는 도메인 이벤트. 수신자(재색인)는 {@code com.recall.search} 모듈에 있어, 이 이벤트로
 * 결합을 끊는다(SettingsService → ReindexService 직접 의존 시 빈 순환: SettingsService → ReindexService →
 * EmbeddingClient → SettingsService).
 */
public record EmbeddingModelChangedEvent() {}
