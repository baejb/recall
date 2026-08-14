package com.recall.llm.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.llm.LlmClient;
import com.recall.llm.LlmConfig;
import com.recall.llm.LlmProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.web.client.RestClient;

/**
 * Anthropic Messages API 어댑터(BYO key). {@link LlmConfig}가 provider=anthropic이고 키가 있을 때 등록한다.
 *
 * <p>구조화 추출·판정은 빠른 비-thinking 응답이 적합해 thinking을 켜지 않는다(Opus 4.8은 thinking 생략 시 thinking 없이 동작). 실패는
 * 삼키지 않고 예외로 드러낸다(조용한 실패 금지). 답변(A)은 {@link #completeStream}으로 SSE 델타를 토큰 단위로 흘린다.
 */
public class AnthropicLlmClient implements LlmClient {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final LlmProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /**
     * Messages API를 {@code stream:true}로 호출하고 SSE 응답을 라인 단위로 읽어 {@code content_block_delta}의 텍스트
     * 델타를 onToken으로 흘린다. 블로킹 방식이지만 호출부(AnswerStreamer)가 가상 스레드에서 돌아 문제되지 않는다. 실패는 예외로 드러낸다.
     */
    @Override
    public void completeStream(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        restClient
                .post()
                .uri("/v1/messages")
                .body(
                        new StreamRequest(
                                props.model(),
                                props.maxTokens(),
                                systemPrompt,
                                List.of(new Message("user", userPrompt)),
                                true))
                .exchange(
                        (request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                String err =
                                        new String(
                                                response.getBody().readAllBytes(),
                                                StandardCharsets.UTF_8);
                                throw new IllegalStateException(
                                        "Anthropic 스트림 오류 "
                                                + response.getStatusCode()
                                                + ": "
                                                + err);
                            }
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    response.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("data:")) {
                                        parseTextDelta(line.substring(5).trim(), objectMapper)
                                                .ifPresent(onToken);
                                    }
                                }
                            }
                            return null;
                        });
    }

    /**
     * SSE {@code data:} 페이로드에서 텍스트 델타를 뽑는다. {@code content_block_delta}의 {@code
     * delta.type=text_delta}일 때만 텍스트를 반환하고, 그 외(하트비트·message_start 등)나 파싱 불가한 라인은 무시한다.
     */
    static Optional<String> parseTextDelta(String dataJson, ObjectMapper mapper) {
        if (dataJson.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(dataJson);
            if (!"content_block_delta".equals(node.path("type").asText())) {
                return Optional.empty();
            }
            JsonNode delta = node.path("delta");
            if (!"text_delta".equals(delta.path("type").asText())) {
                return Optional.empty();
            }
            JsonNode text = delta.get("text");
            return text == null ? Optional.empty() : Optional.of(text.asText());
        } catch (Exception e) {
            return Optional.empty();
        }
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

    record StreamRequest(
            String model, int max_tokens, String system, List<Message> messages, boolean stream) {}

    record Message(String role, String content) {}

    record MessagesResponse(List<Block> content) {}

    record Block(String type, String text) {}
}
