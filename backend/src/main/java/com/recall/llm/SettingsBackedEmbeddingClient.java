package com.recall.llm;

import com.recall.settings.service.SettingsService;

/**
 * 소비자에 주입되던 임베딩 프록시(매 호출 현재 설정으로 팩토리에서 클라이언트를 얻어 위임).
 *
 * @deprecated {@link SettingsBackedLlmClient}과 동일 — 파이프라인은 {@link AiContextFactory#forUser(long)}의
 *     임베딩을 쓴다. @Async/SSE 스레드에서 안전하지 않음. OAuth 후속에서 제거 예정.
 */
@Deprecated
public class SettingsBackedEmbeddingClient implements EmbeddingClient {

    private final SettingsService settings;
    private final EmbeddingClientFactory factory;

    public SettingsBackedEmbeddingClient(SettingsService settings, EmbeddingClientFactory factory) {
        this.settings = settings;
        this.factory = factory;
    }

    private EmbeddingClient current() {
        return factory.forSettings(settings.currentEmbedding());
    }

    @Override
    public int dimension() {
        return current().dimension();
    }

    @Override
    public float[] embedDocument(String text) {
        return current().embedDocument(text);
    }

    @Override
    public float[] embedQuery(String text) {
        return current().embedQuery(text);
    }
}
