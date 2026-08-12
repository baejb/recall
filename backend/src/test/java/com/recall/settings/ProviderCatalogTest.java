package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.recall.settings.ProviderCatalog.Role;
import org.junit.jupiter.api.Test;

class ProviderCatalogTest {

    @Test
    void chatAllowsAnthropicNotVoyage() {
        assertTrue(ProviderCatalog.supports(Role.CHAT, "anthropic"));
        assertFalse(ProviderCatalog.supports(Role.CHAT, "voyage"));
    }

    @Test
    void embeddingAllowsVoyageNotAnthropic() {
        assertTrue(ProviderCatalog.supports(Role.EMBEDDING, "voyage"));
        assertFalse(ProviderCatalog.supports(Role.EMBEDDING, "anthropic"));
    }

    @Test
    void requireSupportedThrowsOnInvalid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProviderCatalog.requireSupported(Role.EMBEDDING, "anthropic"));
    }
}
