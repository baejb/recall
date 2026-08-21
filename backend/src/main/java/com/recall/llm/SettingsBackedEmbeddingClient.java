package com.recall.llm;

import com.recall.settings.SettingsService;

/** 소비자에 주입되는 임베딩 프록시. 매 호출 현재 설정으로 팩토리에서 클라이언트를 얻어 위임한다(런타임 설정 변경 즉시 반영). */
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
