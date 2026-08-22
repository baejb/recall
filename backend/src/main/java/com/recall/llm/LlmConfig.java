package com.recall.llm;

import com.recall.common.secret.SecretCipher;
import com.recall.settings.service.SettingsService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM/임베딩 포트의 기본 빈 등록. 실제 provider 어댑터 빈이 없을 때만 위임 프록시를 등록하고, 어댑터가 등록되면 그것이 우선된다.
 *
 * <p>{@code @ConditionalOnMissingBean}은 {@code @Configuration}의 {@code @Bean} 메서드에서만 신뢰성 있게 동작한다.
 * 스캔되는 {@code @Component}에 직접 붙이면 조건 평가 시점에 자기 자신을 보고 제외해버리는 함정이 있어(그러면 포트를 주입하는 쪽에서 빈을 못 찾는다) 등록을
 * 여기로 모았다.
 *
 * <p>실제 provider 선택/생성 로직은 {@link EmbeddingClientFactory}/{@link LlmClientFactory}로 옮겼다. 여기서 등록하는
 * {@link SettingsBackedEmbeddingClient}/{@link SettingsBackedLlmClient}는 소비자에 주입되는 위임 프록시로, 매 호출
 * {@link SettingsService}의 현재 설정을 팩토리에 넘겨 클라이언트를 얻는다(런타임 설정 변경이 재시작 없이 반영됨).
 */
@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, LlmProperties.class})
public class LlmConfig {

    @Bean
    EmbeddingClientFactory embeddingClientFactory(List<EmbeddingProvider> providers) {
        return new EmbeddingClientFactory(providers);
    }

    @Bean
    LlmClientFactory llmClientFactory(List<ChatProvider> providers) {
        return new LlmClientFactory(providers);
    }

    /**
     * @deprecated 레거시 기본 빈. 멀티유저 전환 후 파이프라인은 사용자별 LLM 을 {@link
     *     com.recall.llm.AiContextFactory#forUser(long)}로 얻는다 — 이 프록시를 직접 주입하지 말 것. 이 프록시는 {@code
     *     SettingsService.currentChat()}(→ {@code CurrentUserProvider})로 현재 사용자를 해석하는데, @Async 저장
     *     잡·SSE 가상 스레드에는 요청 스레드의 소유자가 전파되지 않아 잘못된/부재 사용자로 해석된다(교차유출은 아니고 fail-safe). 현재는 아무 실사용
     *     호출부가 없고 {@code SettingsController}의 설정 표시(현재 사용자)만 {@code currentChat/currentEmbedding}을
     *     쓴다. OAuth 배선 후속에서 이 빈과 {@code SettingsBacked*} 프록시를 제거한다.
     */
    @Deprecated
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(SettingsService settings, LlmClientFactory factory) {
        return new SettingsBackedLlmClient(settings, factory);
    }

    /**
     * @deprecated {@link #llmClient} 과 동일 — 파이프라인은 {@code AiContextFactory.forUser}의 임베딩을 쓴다. OAuth
     *     후속에서 제거.
     */
    @Deprecated
    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient embeddingClient(SettingsService settings, EmbeddingClientFactory factory) {
        return new SettingsBackedEmbeddingClient(settings, factory);
    }

    /** provider 키 at-rest 암호화용 cipher. 마스터키(recall.security.secret-key) 없으면 비활성(fail-closed). */
    @Bean
    SecretCipher secretCipher(@Value("${recall.security.secret-key:}") String key) {
        return new SecretCipher(key);
    }
}
