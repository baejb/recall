package com.recall.settings.service;

import static org.junit.jupiter.api.Assertions.*;

import com.recall.common.exception.ValidationException;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.EmbeddingProvider;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.google.GoogleChatProvider;
import com.recall.llm.provider.google.GoogleEmbeddingProvider;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.service.ProviderCatalog.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderCatalogTest {

    /** 실제 서술자 인스턴스로 카탈로그를 구성한다 — 하드코딩 Set 없이 등록된 서술자에서만 가용성이 파생된다. */
    private ProviderCatalog catalog() {
        return new ProviderCatalog(
                List.of(
                        new AnthropicChatProvider(),
                        new OpenAiChatProvider(),
                        new GoogleChatProvider()),
                List.of(
                        new OpenAiEmbeddingProvider(),
                        new VoyageEmbeddingProvider(),
                        new GoogleEmbeddingProvider()));
    }

    @Test
    void chatAllowsAnthropicNotVoyage() {
        ProviderCatalog c = catalog();
        assertTrue(c.supports(Role.CHAT, "anthropic"));
        assertFalse(c.supports(Role.CHAT, "voyage"));
    }

    @Test
    void embeddingAllowsVoyageNotAnthropic() {
        ProviderCatalog c = catalog();
        assertTrue(c.supports(Role.EMBEDDING, "voyage"));
        assertFalse(c.supports(Role.EMBEDDING, "anthropic"));
    }

    @Test
    void requireSupportedThrowsOnInvalid() {
        ProviderCatalog c = catalog();
        assertThrows(
                ValidationException.class, () -> c.requireSupported(Role.EMBEDDING, "anthropic"));
    }

    @Test
    void chatModelsReflectDescriptors() {
        Map<String, List<String>> chat = catalog().chatModels();
        assertEquals(
                List.of("claude-opus-4-8", "claude-haiku-4-5-20251001"), chat.get("anthropic"));
        assertTrue(chat.containsKey("openai"));
        assertTrue(chat.containsKey("google"));
        assertFalse(chat.containsKey("voyage"));
    }

    @Test
    void embeddingModelsReflectDescriptors() {
        Map<String, List<String>> emb = catalog().embeddingModels();
        assertEquals(List.of("voyage-4-lite", "voyage-4", "voyage-3"), emb.get("voyage"));
        assertTrue(emb.containsKey("openai"));
        assertTrue(emb.containsKey("google"));
        assertFalse(emb.containsKey("anthropic"));
    }

    /**
     * 드리프트 불가 보증: 카탈로그가 EMBEDDING 으로 광고하는 provider 집합과, 팩토리가 실제로 만들 수 있는 provider 집합이 동일한 {@code
     * List<EmbeddingProvider>} 한 원천에서 나온다 — 따라서 카탈로그가 광고한 provider 는 절대 "빌드 불가"일 수 없다.
     */
    @Test
    void catalogAdvertisesExactlyWhatFactoryCanBuild() {
        List<EmbeddingProvider> descriptors =
                List.of(
                        new OpenAiEmbeddingProvider(),
                        new VoyageEmbeddingProvider(),
                        new GoogleEmbeddingProvider());
        ProviderCatalog cat =
                new ProviderCatalog(List.of(new AnthropicChatProvider()), descriptors);
        EmbeddingClientFactory factory = new EmbeddingClientFactory(descriptors);

        for (String provider : cat.embeddingModels().keySet()) {
            // 카탈로그가 광고한 provider 는 팩토리가 반드시 만들 수 있어야 한다(키 유효 시).
            EmbeddingClient built =
                    factory.forSettings(new EmbeddingProperties(provider, "k", null, null, 1024));
            assertNotNull(built);
        }
    }
}
