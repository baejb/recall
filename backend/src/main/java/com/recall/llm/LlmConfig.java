package com.recall.llm;

import com.recall.common.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM/임베딩 포트의 기본 빈 등록. 실제 provider 어댑터 빈이 없을 때만 stub을 등록하고, 어댑터가 등록되면 그것이 우선된다.
 *
 * <p>{@code @ConditionalOnMissingBean}은 {@code @Configuration}의 {@code @Bean} 메서드에서만 신뢰성 있게 동작한다.
 * 스캔되는 {@code @Component}에 직접 붙이면 조건 평가 시점에 자기 자신을 보고 제외해버리는 함정이 있어(그러면 포트를 주입하는 쪽에서 빈을 못 찾는다) 등록을
 * 여기로 모았다.
 */
@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, LlmProperties.class})
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** API 키가 있으면 provider별 어댑터, 없으면 stub. 알 수 없는 provider는 설정 오류이므로 조용히 넘기지 않고 예외로 드러낸다. */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(LlmProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.warn("recall.llm.api-key 미설정 → stub LLM 사용(추출·판정이 fallback 경로)");
            return new StubLlmClient();
        }
        return switch (props.provider().toLowerCase()) {
            case "anthropic" -> new AnthropicLlmClient(props);
            case "openai" -> new OpenAiLlmClient(props);
            case "google" -> new GoogleLlmClient(props);
            default ->
                    throw new IllegalStateException(
                            "알 수 없는 recall.llm.provider: " + props.provider());
        };
    }

    /**
     * API 키가 있으면 provider별 임베딩 어댑터, 없으면 stub. 알 수 없는 provider는 설정 오류이므로 조용히 넘기지 않고 예외로 드러낸다.
     * ({@code @ConditionalOnProperty}는 빈 문자열도 "존재"로 봐서 미설정과 구분이 안 되므로 런타임에 명시적으로 판단한다.)
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient embeddingClient(EmbeddingProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.warn("recall.llm.embedding.api-key 미설정 → stub 임베딩 사용(벡터 검색 무의미)");
            return new StubEmbeddingClient();
        }
        return switch (props.provider().toLowerCase()) {
            case "voyage" -> new VoyageEmbeddingClient(props);
            case "openai" -> new OpenAiEmbeddingClient(props);
            default ->
                    throw new IllegalStateException(
                            "알 수 없는 recall.llm.embedding.provider: " + props.provider());
        };
    }

    /** provider 키 at-rest 암호화용 cipher. 마스터키(recall.security.secret-key) 없으면 비활성(fail-closed). */
    @Bean
    SecretCipher secretCipher(@Value("${recall.security.secret-key:}") String key) {
        return new SecretCipher(key);
    }
}
