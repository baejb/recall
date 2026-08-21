package com.recall.llm.provider.openai;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.EmbeddingProvider;
import java.util.List;
import org.springframework.stereotype.Component;

/** OpenAI embedding provider 서술자(자가 등록). */
@Component
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final List<String> MODELS =
            List.of("text-embedding-3-small", "text-embedding-3-large");

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public List<String> recommendedModels() {
        return MODELS;
    }

    @Override
    public EmbeddingClient create(EmbeddingProperties props) {
        return new OpenAiEmbeddingClient(props);
    }
}
