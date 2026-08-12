package com.recall.llm.provider.voyage;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmConfig;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * Voyage AI 임베딩 어댑터(BYO key). {@link LlmConfig}가 API 키가 설정됐을 때만 이 빈을 등록한다.
 *
 * <p>저장은 {@code input_type=document}, 조회는 {@code input_type=query} 로 요청해 검색 품질을 높인다. 실패는 삼키지 않고 예외로
 * 드러낸다(조용한 실패 금지).
 */
public class VoyageEmbeddingClient implements EmbeddingClient {

    static final String DEFAULT_BASE_URL = "https://api.voyageai.com/v1";
    static final String DEFAULT_MODEL = "voyage-3";

    private final EmbeddingProperties props;
    private final String model;
    private final RestClient restClient;

    public VoyageEmbeddingClient(EmbeddingProperties props) {
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
        return embed(text, "document");
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text, "query");
    }

    private float[] embed(String text, String inputType) {
        EmbeddingResponse body =
                restClient
                        .post()
                        .uri("/embeddings")
                        .body(new EmbeddingRequest(List.of(text), model, inputType))
                        .retrieve()
                        .body(EmbeddingResponse.class);
        if (body == null || body.data() == null || body.data().isEmpty()) {
            throw new IllegalStateException("Voyage 임베딩 응답이 비어 있음");
        }
        List<Float> embedding = body.data().get(0).embedding();
        if (embedding == null || embedding.size() != props.dimension()) {
            throw new IllegalStateException(
                    "Voyage 임베딩 차원 불일치: 기대 "
                            + props.dimension()
                            + ", 실제 "
                            + (embedding == null ? "null" : embedding.size()));
        }
        float[] out = new float[embedding.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = embedding.get(i);
        }
        return out;
    }

    /** Voyage 임베딩 요청 바디. */
    record EmbeddingRequest(List<String> input, String model, String input_type) {}

    /** Voyage 임베딩 응답 바디(필요 필드만). */
    record EmbeddingResponse(List<Item> data) {
        record Item(List<Float> embedding) {}
    }
}
