package com.recall.llm.provider.openai;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmConfig;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 임베딩 어댑터(BYO key). {@link LlmConfig}가 provider=openai이고 키가 있을 때 등록한다.
 *
 * <p>OpenAI text-embedding-3 계열은 {@code dimensions} 파라미터로 출력 차원을 줄일 수 있어, {@code memory_embedding
 * vector(1024)} 에 맞춰 1024로 요청한다(스키마 변경 없이 Voyage와 동일 차원 유지). Voyage와 달리 input_type(document/query)
 * 구분이 없어 저장·조회 임베딩은 동일하다. 실패는 삼키지 않고 예외로 드러낸다(조용한 실패 금지).
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final EmbeddingProperties props;
    private final String model;
    private final RestClient restClient;

    public OpenAiEmbeddingClient(EmbeddingProperties props) {
        this.props = props;
        this.model =
                props.model() == null || props.model().isBlank() ? DEFAULT_MODEL : props.model();
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
                        .uri("/embeddings")
                        .body(new EmbeddingRequest(text, model, props.dimension()))
                        .retrieve()
                        .body(EmbeddingResponse.class);
        return vectorFrom(body, props.dimension());
    }

    /** data[0].embedding을 float[]로 변환하고 기대 차원과 일치하는지 검증한다. */
    static float[] vectorFrom(EmbeddingResponse body, int expectedDim) {
        if (body == null || body.data() == null || body.data().isEmpty()) {
            throw new IllegalStateException("OpenAI 임베딩 응답이 비어 있음");
        }
        List<Float> embedding = body.data().get(0).embedding();
        if (embedding == null || embedding.size() != expectedDim) {
            throw new IllegalStateException(
                    "OpenAI 임베딩 차원 불일치: 기대 "
                            + expectedDim
                            + ", 실제 "
                            + (embedding == null ? "null" : embedding.size()));
        }
        float[] out = new float[embedding.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = embedding.get(i);
        }
        return out;
    }

    /** OpenAI 임베딩 요청 바디. dimensions로 출력 차원을 고정한다. */
    record EmbeddingRequest(String input, String model, int dimensions) {}

    /** OpenAI 임베딩 응답 바디(필요 필드만). */
    record EmbeddingResponse(List<Item> data) {
        record Item(List<Float> embedding) {}
    }
}
