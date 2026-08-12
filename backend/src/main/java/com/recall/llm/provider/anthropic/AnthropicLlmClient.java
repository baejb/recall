package com.recall.llm.provider.anthropic;

import com.recall.llm.LlmClient;
import com.recall.llm.LlmConfig;
import com.recall.llm.LlmProperties;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * Anthropic Messages API 어댑터(BYO key). {@link LlmConfig}가 provider=anthropic이고 키가 있을 때 등록한다.
 *
 * <p>구조화 추출·판정은 빠른 비-thinking 응답이 적합해 thinking을 켜지 않는다(Opus 4.8은 thinking 생략 시 thinking 없이 동작). 실패는
 * 삼키지 않고 예외로 드러낸다(조용한 실패 금지).
 */
public class AnthropicLlmClient implements LlmClient {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final LlmProperties props;
    private final RestClient restClient;

    public AnthropicLlmClient(LlmProperties props) {
        this.props = props;
        String baseUrl =
                props.baseUrl() == null || props.baseUrl().isBlank()
                        ? DEFAULT_BASE_URL
                        : props.baseUrl();
        this.restClient =
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("x-api-key", props.apiKey())
                        .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                        .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        MessagesResponse body =
                restClient
                        .post()
                        .uri("/v1/messages")
                        .body(
                                new MessagesRequest(
                                        props.model(),
                                        props.maxTokens(),
                                        systemPrompt,
                                        List.of(new Message("user", userPrompt))))
                        .retrieve()
                        .body(MessagesResponse.class);
        return textFrom(body);
    }

    /** content 배열의 첫 text 블록에서 텍스트를 뽑는다. */
    static String textFrom(MessagesResponse response) {
        if (response == null || response.content() == null) {
            throw new IllegalStateException("Anthropic 응답이 비어 있음");
        }
        return response.content().stream()
                .filter(b -> "text".equals(b.type()) && b.text() != null)
                .map(Block::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Anthropic 응답에 text 블록이 없음"));
    }

    record MessagesRequest(String model, int max_tokens, String system, List<Message> messages) {}

    record Message(String role, String content) {}

    record MessagesResponse(List<Block> content) {}

    record Block(String type, String text) {}
}
