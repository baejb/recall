package com.recall.llm.provider.openai;

import com.recall.llm.LlmClient;
import com.recall.llm.LlmConfig;
import com.recall.llm.LlmProperties;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * OpenAI Chat Completions API 어댑터(BYO key). {@link LlmConfig}가 provider=openai이고 키가 있을 때 등록한다. 실패는
 * 삼키지 않고 예외로 드러낸다(조용한 실패 금지).
 */
public class OpenAiLlmClient implements LlmClient {

    static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final LlmProperties props;
    private final RestClient restClient;

    public OpenAiLlmClient(LlmProperties props) {
        this.props = props;
        String baseUrl =
                props.baseUrl() == null || props.baseUrl().isBlank()
                        ? DEFAULT_BASE_URL
                        : props.baseUrl();
        this.restClient =
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("Authorization", "Bearer " + props.apiKey())
                        .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        ChatResponse body =
                restClient
                        .post()
                        .uri("/v1/chat/completions")
                        .body(
                                new ChatRequest(
                                        props.model(),
                                        List.of(
                                                new Message("system", systemPrompt),
                                                new Message("user", userPrompt))))
                        .retrieve()
                        .body(ChatResponse.class);
        return textFrom(body);
    }

    /** choices[0].message.content 에서 텍스트를 뽑는다. */
    static String textFrom(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI 응답이 비어 있음");
        }
        Message message = response.choices().get(0).message();
        if (message == null || message.content() == null) {
            throw new IllegalStateException("OpenAI 응답에 message content가 없음");
        }
        return message.content();
    }

    record ChatRequest(String model, List<Message> messages) {}

    record Message(String role, String content) {}

    record ChatResponse(List<Choice> choices) {
        record Choice(Message message) {}
    }
}
