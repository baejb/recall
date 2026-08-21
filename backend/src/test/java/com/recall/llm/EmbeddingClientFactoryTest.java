package com.recall.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.recall.llm.provider.google.GoogleEmbeddingProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingClient;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingClientFactoryTest {

    private EmbeddingClientFactory factory() {
        return new EmbeddingClientFactory(
                List.of(
                        new VoyageEmbeddingProvider(),
                        new OpenAiEmbeddingProvider(),
                        new GoogleEmbeddingProvider()));
    }

    private EmbeddingProperties props(String provider, String key) {
        return new EmbeddingProperties(provider, key, null, null, 1024);
    }

    @Test
    void keyBlankReturnsStub() {
        EmbeddingClient c = factory().forSettings(props("openai", ""));
        assertTrue(c instanceof StubEmbeddingClient);
    }

    @Test
    void openaiProviderReturnsOpenAiClient() {
        EmbeddingClient c = factory().forSettings(props("openai", "sk-x"));
        assertTrue(c instanceof OpenAiEmbeddingClient);
    }

    @Test
    void unknownProviderThrows() {
        // "nope" 는 등록된 서술자에 없다 → 빌드 불가.
        assertThrows(IllegalStateException.class, () -> factory().forSettings(props("nope", "k")));
    }
}
