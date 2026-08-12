package com.recall.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** LLM 설정 → 클라이언트. 동일 설정(provider|model|baseUrl|key 해시)은 캐시 재사용. */
public class LlmClientFactory {

    private final Map<String, LlmClient> cache = new ConcurrentHashMap<>();

    public LlmClient forSettings(LlmProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return new StubLlmClient();
        }
        String cacheKey =
                props.provider()
                        + "|"
                        + props.model()
                        + "|"
                        + props.baseUrl()
                        + "|"
                        + Integer.toHexString(props.apiKey().hashCode());
        return cache.computeIfAbsent(cacheKey, k -> build(props));
    }

    private LlmClient build(LlmProperties props) {
        return switch (props.provider().toLowerCase()) {
            case "anthropic" -> new AnthropicLlmClient(props);
            case "openai" -> new OpenAiLlmClient(props);
            case "google" -> new GoogleLlmClient(props);
            default ->
                    throw new IllegalStateException(
                            "알 수 없는 recall.llm.provider: " + props.provider());
        };
    }
}
