package com.recall.llm;

import com.recall.settings.SettingsService;
import java.util.function.Consumer;

/**
 * 소비자에 주입되던 LLM 프록시(매 호출 현재 설정으로 팩토리에서 클라이언트를 얻어 위임).
 *
 * @deprecated 멀티유저 전환 후 파이프라인은 {@link AiContextFactory#forUser(long)}로 사용자별 LLM 을 얻는다. 이 프록시는 현재 요청
 *     사용자({@code CurrentUserProvider})로 설정을 해석하므로 @Async/SSE 스레드에서 안전하지 않다. OAuth 후속에서 제거 예정.
 */
@Deprecated
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
