package com.recall.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 임베딩 provider 설정(BYO key). 키가 비어 있으면 stub이 대신 쓰인다({@link LlmConfig} 참고).
 *
 * @param apiKey provider API 키(환경변수 VOYAGE_API_KEY). 비면 stub 사용
 * @param model 임베딩 모델(기본 voyage-3)
 * @param baseUrl API 베이스 URL
 * @param dimension 임베딩 차원(memory_embedding vector(N)과 일치해야 함)
 */
@ConfigurationProperties("recall.llm.embedding")
public record EmbeddingProperties(String apiKey, String model, String baseUrl, Integer dimension) {

    public EmbeddingProperties {
        if (model == null || model.isBlank()) model = "voyage-3";
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.voyageai.com/v1";
        if (dimension == null) dimension = 1024;
    }
}
