package com.recall.llm;

import com.recall.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * {@code userId} 소유 설정으로 바인딩된 {@link UserAiContext} 스냅샷을 만든다. {@code forUser} 는 소유권을 추론하지 않는다 —
 * 호출자가 이미 신뢰한 userId 를 그대로 그 사용자 설정 조회에 쓴다(교차유출 방지는 호출자 책임).
 *
 * <p>{@code chatReady}/{@code embeddingReady} 가 false 인 축은 {@code SettingsService#chatFor}/{@code
 * #embeddingFor} 를 아예 호출하지 않는다 — 그 메서드들은 {@code model_setting} 행이 없는 사용자(가입 직후, 설정을 한 번도 만진 적 없음)에
 * 대해 {@code IllegalStateException} 으로 fail-fast 하기 때문이다({@code SettingsService#row}). {@code
 * isChatConfigured}/{@code isEmbeddingConfigured} 는 행 없음을 "미설정"으로 조용히 처리하므로, ready 가 false 면 그 축은
 * stub 클라이언트로 채우고 chatFor/embeddingFor 호출을 건너뛴다 — 미설정 사용자도 예외 없이 컨텍스트를 받는다(차단은
 * requireChat/requireEmbedding 시점에만).
 */
@Component
public class AiContextFactory {

    private final SettingsService settings;
    private final LlmClientFactory llmFactory;
    private final EmbeddingClientFactory embeddingFactory;

    public AiContextFactory(
            SettingsService settings,
            LlmClientFactory llmFactory,
            EmbeddingClientFactory embeddingFactory) {
        this.settings = settings;
        this.llmFactory = llmFactory;
        this.embeddingFactory = embeddingFactory;
    }

    /** {@code userId} 시점의 chat/embedding 설정을 스냅샷으로 고정해 바인딩된 클라이언트를 만든다. */
    public UserAiContext forUser(long userId) {
        boolean chatReady = settings.isChatConfigured(userId);
        boolean embeddingReady = settings.isEmbeddingConfigured(userId);
        LlmClient llm =
                chatReady ? llmFactory.forSettings(settings.chatFor(userId)) : new StubLlmClient();
        EmbeddingClient embedding =
                embeddingReady
                        ? embeddingFactory.forSettings(settings.embeddingFor(userId))
                        : new StubEmbeddingClient();
        return new UserAiContext(userId, llm, embedding, chatReady, embeddingReady);
    }
}
