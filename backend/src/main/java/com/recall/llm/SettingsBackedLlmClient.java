package com.recall.llm;

import com.recall.settings.SettingsService;
import java.util.function.Consumer;

/** 소비자에 주입되는 LLM 프록시. 매 호출 현재 설정으로 팩토리에서 클라이언트를 얻어 위임한다(런타임 설정 변경 즉시 반영). */
public class SettingsBackedLlmClient implements LlmClient {

    private final SettingsService settings;
    private final LlmClientFactory factory;

    public SettingsBackedLlmClient(SettingsService settings, LlmClientFactory factory) {
        this.settings = settings;
        this.factory = factory;
    }

    private LlmClient current() {
        return factory.forSettings(settings.currentChat());
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return current().complete(systemPrompt, userPrompt);
    }

    @Override
    public void completeStream(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        current().completeStream(systemPrompt, userPrompt, onToken);
    }

    @Override
    public boolean available() {
        return current().available();
    }
}
