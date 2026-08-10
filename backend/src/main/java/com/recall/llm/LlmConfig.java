package com.recall.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@EnableConfigurationProperties(EmbeddingProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient stubLlmClient() {
        return new StubLlmClient();
    }

    /**
     * API 키가 있으면 Voyage 어댑터, 없으면 stub. ({@code @ConditionalOnProperty}는 빈 문자열도 "존재"로 봐서 미설정과 구분이 안
     * 되므로 런타임에 명시적으로 판단한다.)
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient embeddingClient(EmbeddingProperties props) {
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            return new VoyageEmbeddingClient(props);
        }
        log.warn("recall.llm.embedding.api-key 미설정 → stub 임베딩 사용(벡터 검색 무의미)");
        return new StubEmbeddingClient();
    }
}
