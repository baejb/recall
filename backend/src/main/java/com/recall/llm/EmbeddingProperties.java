package com.recall.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 임베딩 provider 설정(BYO key). 키가 비어 있으면 stub이 대신 쓰인다({@link LlmConfig} 참고).
 *
 * <p>model·baseUrl 기본값은 provider별 어댑터가 해석한다(LLM 어댑터와 동일 방식) — voyage면 voyage-3, openai면
 * text-embedding-3-small. dimension은 provider 무관하게 {@code memory_embedding vector(N)} 과 일치해야 하므로
 * 여기서 기본값을 둔다.
 *
 * @param provider voyage | openai
 * @param apiKey provider API 키(환경변수 VOYAGE_API_KEY / OPENAI 키). 비면 stub 사용
 * @param model 임베딩 모델(비면 provider별 기본)
 * @param baseUrl API 베이스 URL(비면 provider별 기본)
 * @param dimension 임베딩 차원(memory_embedding vector(N)과 일치해야 함)
 */
@ConfigurationProperties("recall.llm.embedding")
public record EmbeddingProperties(
        String provider, String apiKey, String model, String baseUrl, Integer dimension) {

    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) provider = "voyage";
        if (dimension == null) dimension = 1024;
    }
}
