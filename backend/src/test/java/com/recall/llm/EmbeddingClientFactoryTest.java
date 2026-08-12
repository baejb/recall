package com.recall.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EmbeddingClientFactoryTest {

    private EmbeddingProperties props(String provider, String key) {
        return new EmbeddingProperties(provider, key, null, null, 1024);
    }

    @Test
    void keyBlankReturnsStub() {
        EmbeddingClient c = new EmbeddingClientFactory().forSettings(props("openai", ""));
        assertTrue(c instanceof StubEmbeddingClient);
    }

    @Test
    void openaiProviderReturnsOpenAiClient() {
        EmbeddingClient c = new EmbeddingClientFactory().forSettings(props("openai", "sk-x"));
        assertTrue(c instanceof OpenAiEmbeddingClient);
    }

    @Test
    void unknownProviderThrows() {
        assertThrows(
                IllegalStateException.class,
                () -> new EmbeddingClientFactory().forSettings(props("nope", "k")));
    }
}
