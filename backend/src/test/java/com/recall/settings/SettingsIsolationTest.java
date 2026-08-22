package com.recall.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.recall.common.config.CurrentUserProvider;
import com.recall.common.secret.SecretCipher;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.SettingsService.SettingsUpdate;
import com.recall.settings.repository.ModelSettingRepository;
import com.recall.settings.service.ProviderCatalog;
import com.recall.settings.service.entity.ModelSetting;
import com.recall.settings.service.entity.ModelSettingFixture;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 🔴 릴리스 차단 게이트 — 설정 CRUD 사용자 격리(설계 문서 §9): A가 B의 설정을 조회·변경할 수 없다. {@code currentChat()}/{@code
 * currentEmbedding()}/{@code update()}는 파라미터 없이 {@link CurrentUserProvider}로만 대상 사용자를 해석한다 — 요청 입력에
 * userId 를 실을 자리가 애초에 없다(교차유출 스푸핑 자체가 구조적으로 불가능). 이 테스트는 그 스코프 해석이 실제로 사용자마다 올바르게 갈리는지(항상 같은 행으로 새지
 * 않는지) 회귀로 고정한다.
 *
 * <p>{@code RECALL_SECRET_KEY} 환경변수·실 DB 에 의존하지 않도록, {@code SettingsServiceTest}와 동일하게 자체 생성한
 * {@link SecretCipher} + mock {@link ModelSettingRepository}로 {@code SettingsService}를 직접 구성하고,
 * {@link CurrentUserProvider}는 테스트 중 자유롭게 스위칭 가능한 가변 값으로 대체한다.
 */
class SettingsIsolationTest {

    private static final long USER_A = 701L;
    private static final long USER_B = 702L;

    private final long[] currentUserId = {USER_A};
    private final CurrentUserProvider currentUser = () -> currentUserId[0];

    private ModelSetting rowA;
    private ModelSetting rowB;
    private SettingsService settings;
    private SecretCipher cipher;

    private static SecretCipher realCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    private static ProviderCatalog realCatalog() {
        return new ProviderCatalog(
                List.of(new AnthropicChatProvider(), new OpenAiChatProvider()),
                List.of(new VoyageEmbeddingProvider(), new OpenAiEmbeddingProvider()));
    }

    @BeforeEach
    void setUp() throws Exception {
        rowA = ModelSettingFixture.empty();
        rowA.setChatProvider("anthropic");
        rowA.setChatModel("claude-opus-4-8");
        rowA.setEmbeddingProvider("voyage");
        rowA.setEmbeddingModel("voyage-3");
        rowA.setEmbeddingStatus("READY");

        rowB = ModelSettingFixture.empty();
        rowB.setChatProvider("openai");
        rowB.setChatModel("gpt-4.1");
        rowB.setChatBaseUrl("https://b-initial.example");
        rowB.setEmbeddingProvider("openai");
        rowB.setEmbeddingModel("text-embedding-3-small");
        rowB.setEmbeddingStatus("READY");

        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        when(repo.findByUserId(USER_A)).thenReturn(Optional.of(rowA));
        when(repo.findByUserId(USER_B)).thenReturn(Optional.of(rowB));

        cipher = realCipher();
        settings =
                new SettingsService(
                        repo,
                        cipher,
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        mock(EmbeddingClientFactory.class),
                        realCatalog(),
                        mock(ApplicationEventPublisher.class),
                        currentUser);
        currentUserId[0] = USER_A;
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 currentChat/currentEmbedding: 현재 사용자를 A→B로 바꾸면 B 값이, A로 유지하면 A 값만 보인다(교차 없음)")
    void currentSettingsScopedToCurrentUserOnly() {
        currentUserId[0] = USER_A;
        assertEquals("anthropic", settings.currentChat().provider(), "A는 자신의 chat provider만 봐야 한다");
        assertEquals(
                "voyage",
                settings.currentEmbedding().provider(),
                "A는 자신의 embedding provider만 봐야 한다");

        currentUserId[0] = USER_B;
        assertEquals("openai", settings.currentChat().provider(), "B는 자신의 chat provider만 봐야 한다");
        assertEquals(
                "openai",
                settings.currentEmbedding().provider(),
                "B는 자신의 embedding provider만 봐야 한다");
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 A로 update() 해도 B 행은 전혀 바뀌지 않는다(덮어쓰기 없는 소유자 스코프 UPDATE)")
    void updateAsAOnlyMutatesOwnRowNeverB() {
        currentUserId[0] = USER_A;
        settings.update(
                new SettingsUpdate(
                        null, null, null, "https://a-updated.example", null, null, null, null));

        assertEquals("https://a-updated.example", rowA.getChatBaseUrl(), "A의 변경은 A 행에 반영돼야 한다");
        assertEquals(
                "https://b-initial.example",
                rowB.getChatBaseUrl(),
                "A의 update가 B 행을 건드리면 안 된다(교차 변경 금지) — setUp() 초기값 그대로 남아야 한다");
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 apiKeyConfigured는 사용자별로 갈린다 — B가 미설정이어도 A 조회 결과에 영향 없음")
    void apiKeyConfiguredIsScopedPerUser() {
        // 둘 다 키를 넣지 않은 상태(row는 있지만 chat_api_key_enc 없음) — 부트스트랩이 아니므로 env 폴백도 없다.
        assertFalse(settings.isChatConfigured(USER_A), "A는 키가 없으면 미설정이어야 한다");
        assertFalse(settings.isChatConfigured(USER_B), "B는 키가 없으면 미설정이어야 한다");

        // A만 키를 채우면 A만 true로 바뀌고 B는 그대로 false — 값이 사용자ID로 정확히 갈린다는 근거.
        // settings 가 들고 있는 것과 같은 cipher 로 암호화해야 한다(다른 키로 암호화하면 복호화 자체가 실패한다).
        rowA.setChatApiKeyEnc(cipher.encrypt("sk-a"));
        assertTrue(settings.isChatConfigured(USER_A), "A는 자신의 키가 생기면 설정됨으로 바뀌어야 한다");
        assertFalse(settings.isChatConfigured(USER_B), "A의 키 추가가 B의 설정 상태에 영향을 주면 안 된다");
    }
}
