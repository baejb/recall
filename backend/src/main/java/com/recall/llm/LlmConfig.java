package com.recall.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
public class LlmConfig {

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient stubLlmClient() {
        return new StubLlmClient();
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient stubEmbeddingClient() {
        return new StubEmbeddingClient();
    }
}
