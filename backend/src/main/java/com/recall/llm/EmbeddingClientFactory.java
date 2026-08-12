package com.recall.llm;

import com.recall.llm.provider.google.GoogleEmbeddingClient;
import com.recall.llm.provider.openai.OpenAiEmbeddingClient;
import com.recall.llm.provider.voyage.VoyageEmbeddingClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 임베딩 설정 → 클라이언트. 동일 설정(provider|model|baseUrl|key 해시)은 캐시 재사용. */
public class EmbeddingClientFactory {

    private final Map<String, EmbeddingClient> cache = new ConcurrentHashMap<>();

    public EmbeddingClient forSettings(EmbeddingProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return new StubEmbeddingClient();
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

    private EmbeddingClient build(EmbeddingProperties props) {
        return switch (props.provider().toLowerCase()) {
            case "voyage" -> new VoyageEmbeddingClient(props);
            case "openai" -> new OpenAiEmbeddingClient(props);
            case "google" -> new GoogleEmbeddingClient(props);
            default ->
                    throw new IllegalStateException(
                            "알 수 없는 embedding provider: " + props.provider());
        };
    }
}
