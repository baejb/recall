package com.recall.settings;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.recall.common.secret.SecretCipher;
import com.recall.llm.AiContextFactory;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmClientFactory;
import com.recall.llm.LlmProperties;
import com.recall.llm.UserAiContext;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.anthropic.AnthropicLlmClient;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingClient;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.openai.OpenAiLlmClient;
import com.recall.llm.provider.voyage.VoyageEmbeddingClient;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.repository.ModelSettingRepository;
import com.recall.settings.service.ProviderCatalog;
import com.recall.settings.service.entity.ModelSetting;
import com.recall.settings.service.entity.ModelSettingFixture;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 🔴 릴리스 차단 게이트 — 키 사용 격리(설계 문서 §9): 서로 다른 provider/키를 가진 두 사용자가 {@link
 * AiContextFactory#forUser(long)}를 통해 각자의 클라이언트에만 바인딩되는지(교차 없음) 검증한다.
 *
 * <p>실 DB·{@code RECALL_SECRET_KEY} 환경변수에 의존하지 않도록, {@code SettingsServiceTest}/{@code
 * ReindexUserScopeTest}와 동일하게 자체 생성한 {@link SecretCipher} + mock {@link ModelSettingRepository}로
 * {@code SettingsService}를 직접 구성한다. {@code LlmClientFactory}/{@code EmbeddingClientFactory}는 실제
 * provider 서술자로 구성해, {@code forUser}가 반환하는 클라이언트의 구체 타입(provider별로 다른 클래스)으로 교차 여부를 판별한다 —
 * provider/키가 잘못 섞이면 타입 또는 인스턴스가 어긋나 즉시 드러난다.
 */
class AiContextFactoryKeyIsolationTest {

    private static final long USER_A = 501L;
    private static final long USER_B = 502L;

    private static SecretCipher realCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 서로 다른 provider/키를 가진 두 사용자는 forUser에서 각자의 클라이언트로만 바인딩된다(교차 없음)")
    void forUserBindsEachUserToOwnProviderAndKey() throws Exception {
        SecretCipher cipher = realCipher();

        ModelSetting rowA = ModelSettingFixture.empty();
        rowA.setChatProvider("anthropic");
        rowA.setChatModel("claude-opus-4-8");
        rowA.setChatApiKeyEnc(cipher.encrypt("sk-user-a-chat"));
        rowA.setEmbeddingProvider("voyage");
        rowA.setEmbeddingModel("voyage-3");
        rowA.setEmbeddingApiKeyEnc(cipher.encrypt("sk-user-a-embedding"));
        rowA.setEmbeddingStatus("READY");

        ModelSetting rowB = ModelSettingFixture.empty();
        rowB.setChatProvider("openai");
        rowB.setChatModel("gpt-4.1");
        rowB.setChatApiKeyEnc(cipher.encrypt("sk-user-b-chat"));
        rowB.setEmbeddingProvider("openai");
        rowB.setEmbeddingModel("text-embedding-3-small");
        rowB.setEmbeddingApiKeyEnc(cipher.encrypt("sk-user-b-embedding"));
        rowB.setEmbeddingStatus("READY");

        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        when(repo.findByUserId(USER_A)).thenReturn(Optional.of(rowA));
        when(repo.findByUserId(USER_B)).thenReturn(Optional.of(rowB));

        ProviderCatalog catalog =
                new ProviderCatalog(
                        List.of(new AnthropicChatProvider(), new OpenAiChatProvider()),
                        List.of(new VoyageEmbeddingProvider(), new OpenAiEmbeddingProvider()));

        SettingsService settings =
                new SettingsService(
                        repo,
                        cipher,
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        mock(EmbeddingClientFactory.class), // update()의 probe 전용, 이 테스트에선 안 씀
                        catalog,
                        mock(ApplicationEventPublisher.class),
                        () -> USER_A); // forUser(명시적 userId) 경로에선 currentUser 가 쓰이지 않는다

        AiContextFactory factory =
                new AiContextFactory(
                        settings,
                        new LlmClientFactory(
                                List.of(new AnthropicChatProvider(), new OpenAiChatProvider())),
                        new EmbeddingClientFactory(
                                List.of(
                                        new VoyageEmbeddingProvider(),
                                        new OpenAiEmbeddingProvider())));

        UserAiContext ctxA = factory.forUser(USER_A);
        UserAiContext ctxB = factory.forUser(USER_B);

        assertTrue(ctxA.chatReady(), "A는 chat 설정 완료 상태여야 한다");
        assertTrue(ctxA.embeddingReady(), "A는 embedding 설정 완료 상태여야 한다");
        assertTrue(ctxB.chatReady(), "B는 chat 설정 완료 상태여야 한다");
        assertTrue(ctxB.embeddingReady(), "B는 embedding 설정 완료 상태여야 한다");

        assertInstanceOf(
                AnthropicLlmClient.class, ctxA.llm(), "A는 자신의 provider(anthropic)로 바인딩돼야 한다");
        assertInstanceOf(OpenAiLlmClient.class, ctxB.llm(), "B는 자신의 provider(openai)로 바인딩돼야 한다");
        assertInstanceOf(
                VoyageEmbeddingClient.class,
                ctxA.embedding(),
                "A는 자신의 embedding provider(voyage)로 바인딩돼야 한다");
        assertInstanceOf(
                OpenAiEmbeddingClient.class,
                ctxB.embedding(),
                "B는 자신의 embedding provider(openai)로 바인딩돼야 한다");

        assertNotSame(ctxA.llm(), ctxB.llm(), "서로 다른 사용자의 키는 같은 클라이언트 인스턴스를 공유하면 안 된다");
        assertNotSame(ctxA.embedding(), ctxB.embedding(), "서로 다른 사용자의 임베딩 키는 인스턴스를 공유하면 안 된다");
    }
}
