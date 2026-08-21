package com.recall.llm.provider.voyage;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.EmbeddingProvider;
import java.util.List;
import org.springframework.stereotype.Component;

/** Voyage embedding provider 서술자(자가 등록). */
@Component
public class VoyageEmbeddingProvider implements EmbeddingProvider {

    private static final List<String> MODELS = List.of("voyage-4-lite", "voyage-4", "voyage-3");

    @Override
    public String name() {
        return "voyage";
    }

    @Override
    public List<String> recommendedModels() {
        return MODELS;
    }

    @Override
    public EmbeddingClient create(EmbeddingProperties props) {
        return new VoyageEmbeddingClient(props);
    }
}
