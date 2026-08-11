package com.recall.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM provider 설정(BYO key). 활성 provider 하나를 선택해 단일 키/모델로 호출한다. 키가 비어 있으면 stub이 대신 쓰인다({@link
 * LlmConfig} 참고).
 *
 * @param provider anthropic | openai | google
 * @param apiKey provider API 키(환경변수 RECALL_LLM_API_KEY). 비면 stub 사용
 * @param model 모델 ID(예: claude-opus-4-8, gpt-*, gemini-*)
 * @param baseUrl API 베이스 URL. 비면 provider 기본값 사용
 * @param maxTokens 응답 최대 토큰(Anthropic은 필수). 기본 4096
 */
@ConfigurationProperties("recall.llm")
public record LlmProperties(
        String provider, String apiKey, String model, String baseUrl, Integer maxTokens) {

    public LlmProperties {
        if (provider == null || provider.isBlank()) provider = "anthropic";
        if (model == null || model.isBlank()) model = "claude-opus-4-8";
        if (maxTokens == null) maxTokens = 4096;
    }
}
