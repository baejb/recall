package com.recall.llm.provider.google;

import com.recall.llm.ChatProvider;
import com.recall.llm.LlmClient;
import com.recall.llm.LlmProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/** Google chat provider 서술자(자가 등록). */
@Component
public class GoogleChatProvider implements ChatProvider {

    private static final List<String> MODELS = List.of("gemini-2.5-pro", "gemini-2.5-flash");

    @Override
    public String name() {
        return "google";
    }

    @Override
    public List<String> recommendedModels() {
        return MODELS;
    }

    @Override
    public LlmClient create(LlmProperties props) {
        return new GoogleLlmClient(props);
    }
}
