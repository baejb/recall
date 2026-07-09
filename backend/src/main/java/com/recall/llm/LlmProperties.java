package com.recall.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External LLM configuration. Recall has no GPU of its own — the user brings their
 * own API key (BYO key), so there is no billing logic.
 */
@ConfigurationProperties(prefix = "recall.llm")
public record LlmProperties(
        String apiKey,
        String baseUrl,
        String modelStrong,
        String modelCheap,
        String modelEmbed
) {
}
