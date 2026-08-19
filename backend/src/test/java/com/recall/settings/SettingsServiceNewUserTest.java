package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.common.config.CurrentUserProvider;
import com.recall.common.secret.SecretCipher;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.google.GoogleEmbeddingProvider;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.SettingsService.SettingsUpdate;
import com.recall.settings.repository.ModelSettingRepository;
import com.recall.settings.service.ProviderCatalog;
import com.recall.settings.service.entity.ModelSetting;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 신규 사용자(자기 model_setting 행이 아직 없음) 온보딩 회귀. 과거엔 조회/최초 저장이 {@code row()}에서 {@link
 * IllegalStateException}을 던져 500 이 나 BYO 키를 등록할 길이 없었다 — GET 은 미설정 기본 뷰(쓰기 없음), 최초 PUT 은 기본 행을
 * 만들어(upsert) 입력을 얹어야 한다.
 *
 * <p>부트스트랩(1)이 아닌 사용자를 쓴다 — env 폴백 대상이 아니므로 DB 키가 없으면 순수 미설정 상태다.
 */
class SettingsServiceNewUserTest {

    private static final long NEW_USER = 2L; // 부트스트랩(1) 아님
    private static final CurrentUserProvider NEW_USER_PROVIDER = () -> NEW_USER;

    /** 키 회전을 저장할 수 있게 활성화된(fail-closed 아닌) cipher. */
    private static SecretCipher enabledCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    private static ProviderCatalog realCatalog() {
        return new ProviderCatalog(
                List.of(new AnthropicChatProvider(), new OpenAiChatProvider()),
                List.of(
                        new OpenAiEmbeddingProvider(),
                        new VoyageEmbeddingProvider(),
                        new GoogleEmbeddingProvider()));
    }

    private SettingsService newService(
            ModelSettingRepository repo,
            EmbeddingClientFactory factory,
            ApplicationEventPublisher publisher)
            throws Exception {
        // env 키는 non-blank 로 둔다 — 신규 사용자(비부트스트랩)에겐 폴백되면 안 됨을 함께 확인.
        return new SettingsService(
                repo,
                enabledCipher(),
                new EmbeddingProperties("voyage", "sk-env-emb", null, null, 1024),
                new LlmProperties("anthropic", "sk-env-chat", null, null, 4096),
                factory,
                realCatalog(),
                publisher,
                NEW_USER_PROVIDER);
    }

    @Test
    @DisplayName("행 없는 신규 사용자 GET — 예외 없이 미설정 기본 뷰(쓰기 없음)")
    void newUserGetReturnsUnconfiguredDefaultWithoutWriting() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        when(repo.findByUserId(NEW_USER)).thenReturn(Optional.empty());
        SettingsService svc =
                newService(
                        repo,
                        mock(EmbeddingClientFactory.class),
                        mock(ApplicationEventPublisher.class));

        LlmProperties chat = svc.chatFor(NEW_USER);
        EmbeddingProperties emb = svc.embeddingFor(NEW_USER);

        // 기본 provider 뷰가 보이되, 키는 없다(비부트스트랩 → env 폴백 없음 → 미설정).
        assertEquals("anthropic", chat.provider());
        assertNull(chat.apiKey(), "신규 사용자는 DB 키도 env 폴백도 없어 chat 키가 없어야 한다");
        assertEquals("voyage", emb.provider());
        assertNull(emb.apiKey(), "신규 사용자는 embedding 키가 없어야 한다");
        assertEquals("READY", svc.embeddingStatus(NEW_USER));

        // 조회는 절대 행을 심지 않는다.
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("신규 사용자 최초 PUT — 기본 행을 만들어 입력을 얹고 저장(upsert)")
    void newUserFirstPutCreatesRow() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        when(repo.findByUserId(NEW_USER)).thenReturn(Optional.empty());
        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        // chat 키만 등록(provider 동일 → 프로브·재색인 없음).
        svc.update(new SettingsUpdate(null, null, "sk-user2-chat", null, null, null, null, null));

        ArgumentCaptor<ModelSetting> saved = ArgumentCaptor.forClass(ModelSetting.class);
        verify(repo).save(saved.capture());
        assertEquals(NEW_USER, saved.getValue().getUserId(), "새 행은 현재 사용자 소유여야 한다");
        assertEquals("anthropic", saved.getValue().getChatProvider(), "미지정 필드는 기본값 유지");
        assertNotNull(saved.getValue().getChatApiKeyEnc(), "입력한 chat 키가 암호화돼 저장돼야 한다");

        // chat 전용 변경이므로 임베딩 프로브·재색인은 없다.
        verify(factory, never()).forSettings(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("신규 사용자 최초 PUT(임베딩 설정) — 프로브 성공 후 행 저장 + 재색인 트리거")
    void newUserFirstPutWithEmbeddingProbesThenSavesAndReindexes() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        when(repo.findByUserId(NEW_USER)).thenReturn(Optional.empty());

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        // 임베딩 provider 를 openai 로 + 유효 키 → 프로브가 돌고 성공하면 새 행이 저장된다.
        svc.update(new SettingsUpdate(null, null, null, null, "openai", null, "sk-emb-new", null));

        verify(good).embedDocument("probe");
        ArgumentCaptor<ModelSetting> saved = ArgumentCaptor.forClass(ModelSetting.class);
        verify(repo).save(saved.capture());
        assertEquals(NEW_USER, saved.getValue().getUserId());
        assertEquals("openai", saved.getValue().getEmbeddingProvider());
        assertEquals("REINDEXING", saved.getValue().getEmbeddingStatus());
        assertNotNull(saved.getValue().getEmbeddingApiKeyEnc());
        verify(publisher).publishEvent(any(EmbeddingModelChangedEvent.class));
    }
}
