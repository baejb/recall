package com.recall.llm.provider.google;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.EmbeddingProvider;
import java.util.List;
import org.springframework.stereotype.Component;

/** Google embedding provider 서술자(자가 등록). */
@Component
public class GoogleEmbeddingProvider implements EmbeddingProvider {

    private static final List<String> MODELS = List.of("gemini-embedding-001");

    @Override
    public String name() {
        return "google";
    }

    @Override
    public List<String> recommendedModels() {
        return MODELS;
    }

    @Override
    public EmbeddingClient create(EmbeddingProperties props) {
        return new GoogleEmbeddingClient(props);
    }
}
