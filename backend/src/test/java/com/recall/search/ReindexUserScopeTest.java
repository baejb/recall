package com.recall.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.CurrentUserProvider;
import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.llm.UserAiContext;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.EmbeddingModelChangedEvent;
import com.recall.settings.ModelSetting;
import com.recall.settings.ModelSettingRepository;
import com.recall.settings.ProviderCatalog;
import com.recall.settings.SettingsService;
import com.recall.settings.SettingsService.SettingsUpdate;
import com.recall.settings.SettingsService.UpdateResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 🔴 릴리스 차단 게이트 — 재색인 사용자별 격리(불변 원칙: 교차유출 금지) + 세대 펜싱 회귀(설계 문서 §6). 격리·펜싱 두 케이스는 실 DB(user_id 스코프
 * UPDATE 가 SQL 레벨에서 실제로 다른 사용자 행을 안 건드리는지)로 검증한다 — mock 은 SQL WHERE 절 자체를 증명하지 못한다.
 *
 * <p>base-url 트리거 검증은 {@link SettingsService#update}의 결정론적 분기라 DB 없이 Mockito 단위로 고정한다(빠르고 재현적).
 */
@SpringBootTest
class ReindexUserScopeTest {

    @Autowired private ReindexService reindexService;
    @Autowired private ModelSettingRepository settingRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        userIds.forEach(id -> jdbc.update("DELETE FROM model_setting WHERE user_id=?", id));
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id=?", id));
        userIds.clear();
    }

    private long seedUser(String subject) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO app_user (provider, subject, display_name) "
                                + "VALUES ('test', ?, ?) RETURNING id",
                        Long.class,
                        subject,
                        subject);
        userIds.add(id);
        return id;
    }

    /** id=user_id 로 심는다 — model_setting.id 는 시퀀스가 아니라 명시적 PK 라 다른 테스트의 부트스트랩(id=1)과 충돌하지 않는다. */
    private void seedModelSetting(long userId, String status, long generation) {
        jdbc.update(
                "INSERT INTO model_setting "
                        + "(id, user_id, chat_provider, chat_model, embedding_provider, embedding_model, "
                        + "embedding_status, embedding_generation) "
                        + "VALUES (?, ?, 'anthropic', 'claude-x', 'voyage', 'voyage-3', ?, ?)",
                userId,
                userId,
                status,
                generation);
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 A 재색인 실패가 B의 embedding_status를 바꾸지 않는다(user_id 스코프 UPDATE)")
    void reindexFailureIsolatedByUser() {
        long userA = seedUser("reindex-scope-a");
        long userB = seedUser("reindex-scope-b");
        seedModelSetting(userA, "REINDEXING", 1L);
        seedModelSetting(userB, "READY", 0L);

        // embedding 미준비 컨텍스트 → requireEmbedding() 이 곧장 실패해 재색인이 FAILED 로 끝난다.
        UserAiContext failingCtx = new UserAiContext(userA, null, null, false, false);

        reindexService.reindexUser(userA, 1L, failingCtx);

        assertEquals(
                "FAILED",
                settingRepository.findByUserId(userA).orElseThrow().getEmbeddingStatus(),
                "A는 embedding 미준비 컨텍스트로 실패해 FAILED로 전이돼야 한다");
        assertEquals(
                "READY",
                settingRepository.findByUserId(userB).orElseThrow().getEmbeddingStatus(),
                "A의 재색인 실패가 B 행을 건드리면 안 된다(user_id 스코프 UPDATE, 교차유출 금지)");
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 stale generation(1) UPDATE는 A의 현재 generation(2) 상태를 덮어쓰지 못한다(세대 펜싱)")
    void staleGenerationFenced() {
        long userA = seedUser("reindex-scope-gen");
        // 더 새로운 잡(generation=2)이 이미 이 행을 REINDEXING 으로 진행 중이라고 가정.
        seedModelSetting(userA, "REINDEXING", 2L);

        int updated = settingRepository.updateEmbeddingStatusIfGeneration(userA, "READY", 1L);

        assertEquals(0, updated, "stale generation(1) 조건은 0행 매치여야 한다(현재 세대는 2)");
        assertEquals(
                "REINDEXING",
                settingRepository.findByUserId(userA).orElseThrow().getEmbeddingStatus(),
                "새 세대(2) 잡의 진행 상태가 옛 세대(1) 잡에 덮어써지면 안 된다");
    }

    // ── base-url 변경 재색인 트리거 (Mockito 단위 — SettingsService 결정론 분기, DB 불필요) ──

    private static SecretCipher realCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    @Test
    @DisplayName("embedding base URL 변경은 provider/model이 그대로여도 재색인을 트리거한다")
    void baseUrlChangeTriggersReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = mock(ModelSetting.class);
        when(seed.getChatProvider()).thenReturn("anthropic");
        when(seed.getChatModel()).thenReturn("claude-opus-4-8");
        when(seed.getEmbeddingProvider()).thenReturn("voyage");
        when(seed.getEmbeddingModel()).thenReturn("voyage-3");
        when(seed.getEmbeddingStatus()).thenReturn("READY");
        when(seed.getEmbeddingBaseUrl()).thenReturn(null); // 기존 base-url 없음 → 새 값과 다름
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider bootstrapUser = () -> 1L;
        ProviderCatalog catalog =
                new ProviderCatalog(
                        List.of(new AnthropicChatProvider()),
                        List.of(new VoyageEmbeddingProvider()));

        SettingsService svc =
                new SettingsService(
                        repo,
                        realCipher(),
                        new EmbeddingProperties("voyage", "sk-env-key", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        factory,
                        catalog,
                        publisher,
                        bootstrapUser);

        UpdateResult result =
                svc.update(
                        new SettingsUpdate(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "https://new-embed-base.example"));

        verify(factory).forSettings(any());
        verify(good).embedDocument("probe");
        verify(publisher).publishEvent(any(EmbeddingModelChangedEvent.class));
        assertTrue(result.embeddingChanged(), "provider/model 동일해도 base-url 변경은 재색인을 트리거해야 한다");
        verify(seed).setEmbeddingBaseUrl("https://new-embed-base.example");
    }

    @Test
    @DisplayName("API 키만 회전(provider/model/base-url 동일)하면 재색인을 트리거하지 않는다")
    void apiKeyOnlyChangeDoesNotTriggerReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = mock(ModelSetting.class);
        when(seed.getChatProvider()).thenReturn("anthropic");
        when(seed.getChatModel()).thenReturn("claude-opus-4-8");
        when(seed.getEmbeddingProvider()).thenReturn("voyage");
        when(seed.getEmbeddingModel()).thenReturn("voyage-3");
        when(seed.getEmbeddingStatus()).thenReturn("READY");
        when(seed.getEmbeddingBaseUrl()).thenReturn(null);
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider bootstrapUser = () -> 1L;
        ProviderCatalog catalog =
                new ProviderCatalog(
                        List.of(new AnthropicChatProvider()),
                        List.of(new VoyageEmbeddingProvider()));

        SettingsService svc =
                new SettingsService(
                        repo,
                        realCipher(),
                        new EmbeddingProperties("voyage", "sk-env-key", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        factory,
                        catalog,
                        publisher,
                        bootstrapUser);

        // provider/model/base-url 은 그대로(null=변경 없음), 임베딩 키만 회전.
        UpdateResult result =
                svc.update(
                        new SettingsUpdate(
                                null, null, null, null, null, null, "sk-rotated-only", null));

        verify(publisher, never()).publishEvent(any());
        assertFalse(result.embeddingChanged(), "API 키만 교체는 재색인을 트리거하면 안 된다(설계 문서 §6)");
    }
}
