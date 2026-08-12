package com.recall.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 역할별 허용 provider·모델(정적 카탈로그) + 검증. capability 비대칭(설계 §2.1). */
public final class ProviderCatalog {

    public enum Role {
        CHAT,
        EMBEDDING
    }

    private static final Set<String> CHAT_PROVIDERS = Set.of("anthropic", "openai", "google");
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of("openai", "voyage", "google");

    private static final Map<String, List<String>> CHAT_MODELS =
            Map.of(
                    "anthropic", List.of("claude-opus-4-8", "claude-haiku-4-5-20251001"),
                    "openai", List.of("gpt-4.1", "gpt-4.1-mini"),
                    "google", List.of("gemini-2.5-pro", "gemini-2.5-flash"));

    private static final Map<String, List<String>> EMBEDDING_MODELS =
            Map.of(
                    "openai", List.of("text-embedding-3-small", "text-embedding-3-large"),
                    "voyage", List.of("voyage-4-lite", "voyage-4", "voyage-3"),
                    "google", List.of("gemini-embedding-001"));

    private ProviderCatalog() {}

    public static boolean supports(Role role, String provider) {
        return (role == Role.CHAT ? CHAT_PROVIDERS : EMBEDDING_PROVIDERS).contains(provider);
    }

    public static void requireSupported(Role role, String provider) {
        if (!supports(role, provider)) {
            throw new IllegalArgumentException(role + " 역할이 지원하지 않는 provider: " + provider);
        }
    }

    public static Map<String, List<String>> chatModels() {
        return CHAT_MODELS;
    }

    public static Map<String, List<String>> embeddingModels() {
        return EMBEDDING_MODELS;
    }
}
