package com.recall.llm.provider.openai;

import com.recall.llm.ChatProvider;
import com.recall.llm.LlmClient;
import com.recall.llm.LlmProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/** OpenAI chat provider 서술자(자가 등록). */
@Component
public class OpenAiChatProvider implements ChatProvider {

    private static final List<String> MODELS = List.of("gpt-4.1", "gpt-4.1-mini");

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public List<String> recommendedModels() {
        return MODELS;
    }

    @Override
    public LlmClient create(LlmProperties props) {
        return new OpenAiLlmClient(props);
    }
}
