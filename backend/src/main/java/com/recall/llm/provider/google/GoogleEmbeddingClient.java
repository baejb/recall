package com.recall.llm.provider.google;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmConfig;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * Google Gemini embedContent API 어댑터(BYO key). {@link LlmConfig}가 provider=google이고 키가 있을 때 등록한다.
 * 키는 쿼리 파라미터로 전달한다.
 *
 * <p>{@code outputDimensionality}로 출력 차원을 {@code memory_embedding vector(1024)} 에 맞춰 요청한다(스키마 변경 없이
 * Voyage/OpenAI와 동일 차원 유지). Google embedContent는 input_type(document/query) 구분이 없어 저장·조회 임베딩이 동일하다.
 * 실패는 삼키지 않고 예외로 드러낸다(조용한 실패 금지). 키가 URL 쿼리에 실리므로 예외 메시지에 URL/키를 담지 않는다.
 */
public class GoogleEmbeddingClient implements EmbeddingClient {

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    static final String DEFAULT_MODEL = "gemini-embedding-001";

    private final EmbeddingProperties props;
    private final String model;
    private final RestClient restClient;

    public GoogleEmbeddingClient(EmbeddingProperties props) {
        this.props = props;
        this.model =
                props.model() == null || props.model().isBlank() ? DEFAULT_MODEL : props.model();
        String baseUrl =
                props.baseUrl() == null || props.baseUrl().isBlank()
                        ? DEFAULT_BASE_URL
                        : props.baseUrl();
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public int dimension() {
        return props.dimension();
    }

    @Override
    public float[] embedDocument(String text) {
        return embed(text);
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text);
    }

    private float[] embed(String text) {
        EmbeddingResponse body =
                restClient
                        .post()
                        .uri("/v1beta/models/{model}:embedContent?key={key}", model, props.apiKey())
                        .body(
                                new EmbeddingRequest(
                                        "models/" + model,
                                        new Content(List.of(new Part(text))),
                                        props.dimension()))
                        .retrieve()
                        .body(EmbeddingResponse.class);
        return vectorFrom(body, props.dimension());
    }

    /** embedding.values를 float[]로 변환하고 기대 차원과 일치하는지 검증한다. */
    static float[] vectorFrom(EmbeddingResponse body, int expectedDim) {
        if (body == null || body.embedding() == null || body.embedding().values() == null) {
            throw new IllegalStateException("Google 임베딩 응답이 비어 있음");
        }
        List<Float> values = body.embedding().values();
        if (values.size() != expectedDim) {
            throw new IllegalStateException(
                    "Google 임베딩 차원 불일치: 기대 " + expectedDim + ", 실제 " + values.size());
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** Google 임베딩 요청 바디. outputDimensionality로 출력 차원을 고정한다. */
    record EmbeddingRequest(String model, Content content, int outputDimensionality) {}

    record Content(List<Part> parts) {}

    record Part(String text) {}

    /** Google 임베딩 응답 바디(필요 필드만). */
    record EmbeddingResponse(Embedding embedding) {
        record Embedding(List<Float> values) {}
    }
}
