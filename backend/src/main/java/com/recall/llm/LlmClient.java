package com.recall.llm;

import org.springframework.stereotype.Component;

/**
 * Thin wrapper over the external managed LLM / embedding API (BYO key).
 *
 * <p>Masking must already have run before any text reaches this client
 * (non-negotiable rule: mask before external send).
 */
@Component
public class LlmClient {

    private final LlmProperties props;

    public LlmClient(LlmProperties props) {
        this.props = props;
    }

    /** Structured-output completion against the given model tier. */
    public String complete(String model, String prompt) {
        // TODO: HTTP call to props.baseUrl() with props.apiKey(); enforce JSON schema output.
        throw new UnsupportedOperationException("LlmClient.complete not implemented yet");
    }

    /** Embedding for a search expression (not raw text). */
    public float[] embed(String text) {
        // TODO: HTTP call to the embedding endpoint (props.modelEmbed()).
        throw new UnsupportedOperationException("LlmClient.embed not implemented yet");
    }

    public LlmProperties props() {
        return props;
    }
}
