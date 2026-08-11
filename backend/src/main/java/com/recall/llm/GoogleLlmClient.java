package com.recall.llm;

import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * Google Gemini generateContent API 어댑터(BYO key). {@link LlmConfig}가 provider=google이고 키가 있을 때
 * 등록한다. 키는 쿼리 파라미터로 전달한다. 실패는 삼키지 않고 예외로 드러낸다(조용한 실패 금지).
 */
public class GoogleLlmClient implements LlmClient {

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final LlmProperties props;
    private final RestClient restClient;

    public GoogleLlmClient(LlmProperties props) {
        this.props = props;
        String baseUrl =
                props.baseUrl() == null || props.baseUrl().isBlank()
                        ? DEFAULT_BASE_URL
                        : props.baseUrl();
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        GenerateResponse body =
                restClient
                        .post()
                        .uri(
                                "/v1beta/models/{model}:generateContent?key={key}",
                                props.model(),
                                props.apiKey())
                        .body(
                                new GenerateRequest(
                                        new SystemInstruction(List.of(new Part(systemPrompt))),
                                        List.of(
                                                new Content(
                                                        "user", List.of(new Part(userPrompt))))))
                        .retrieve()
                        .body(GenerateResponse.class);
        return textFrom(body);
    }

    /** candidates[0].content.parts[0].text 에서 텍스트를 뽑는다. */
    static String textFrom(GenerateResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new IllegalStateException("Gemini 응답이 비어 있음");
        }
        Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            throw new IllegalStateException("Gemini 응답에 parts가 없음");
        }
        String text = content.parts().get(0).text();
        if (text == null) {
            throw new IllegalStateException("Gemini 응답 part에 text가 없음");
        }
        return text;
    }

    record GenerateRequest(SystemInstruction systemInstruction, List<Content> contents) {}

    record SystemInstruction(List<Part> parts) {}

    record Content(String role, List<Part> parts) {}

    record Part(String text) {}

    record GenerateResponse(List<Candidate> candidates) {
        record Candidate(Content content) {}
    }
}
