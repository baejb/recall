package com.recall.llm.provider.anthropic;

import com.recall.llm.ChatProvider;
import com.recall.llm.LlmClient;
import com.recall.llm.LlmProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/** Anthropic chat provider 서술자(자가 등록). */
@Component
public class AnthropicChatProvider implements ChatProvider {

    private static final List<String> MODELS =
            List.of("claude-opus-4-8", "claude-haiku-4-5-20251001");

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public List<String> recommendedModels() {
        return MODELS;
    }

    @Override
    public LlmClient create(LlmProperties props) {
        return new AnthropicLlmClient(props);
    }
}
