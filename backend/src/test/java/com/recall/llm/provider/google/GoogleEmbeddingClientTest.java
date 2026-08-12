package com.recall.llm.provider.google;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Google 임베딩 응답 JSON → 벡터 추출(vectorFrom)의 결정론 검증. 실제 HTTP 호출은 부팅 스모크로 확인하고, 여기선 파싱·차원검증만 본다(기존
 * OpenAI 임베딩 파싱 테스트와 같은 결).
 */
class GoogleEmbeddingClientTest {

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("Google: embedding.values를 차원대로 뽑는다")
    void google() throws Exception {
        String json =
                """
                {"embedding":{"values":[0.1,0.2,0.3]}}
                """;
        var resp = mapper.readValue(json, GoogleEmbeddingClient.EmbeddingResponse.class);
        assertArrayEquals(
                new float[] {0.1f, 0.2f, 0.3f}, GoogleEmbeddingClient.vectorFrom(resp, 3), 1e-6f);
    }

    @Test
    @DisplayName("Google: 차원이 기대와 다르면 예외(조용한 실패 금지)")
    void googleDimensionMismatch() throws Exception {
        String json =
                """
                {"embedding":{"values":[0.1,0.2,0.3]}}
                """;
        var resp = mapper.readValue(json, GoogleEmbeddingClient.EmbeddingResponse.class);
        assertThrows(
                IllegalStateException.class, () -> GoogleEmbeddingClient.vectorFrom(resp, 1024));
    }

    @Test
    @DisplayName("Google: values가 비면 예외")
    void googleEmpty() throws Exception {
        var resp =
                mapper.readValue(
                        "{\"embedding\":{\"values\":[]}}",
                        GoogleEmbeddingClient.EmbeddingResponse.class);
        assertThrows(
                IllegalStateException.class, () -> GoogleEmbeddingClient.vectorFrom(resp, 1024));
    }
}
