package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.common.BadRequestException;
import com.recall.common.CurrentUserProvider;
import com.recall.common.SecretCipher;
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
import com.recall.settings.SettingsService.UpdateResult;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

class SettingsServiceProbeTest {

    /** 테스트는 전부 부트스트랩 사용자(1) 스코프 — 기존(단일 사용자) 동작을 그대로 검증한다. */
    private static final CurrentUserProvider BOOTSTRAP_USER = () -> 1L;

    /** 키 회전을 저장할 수 있게 활성화된(fail-closed 아닌) cipher — fail-closed 자체는 SettingsServiceTest에서 검증한다. */
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
        return new SettingsService(
                repo,
                enabledCipher(),
                new EmbeddingProperties("voyage", "sk-env-key", null, null, 1024),
                new LlmProperties("anthropic", "", null, null, 4096),
                factory,
                realCatalog(),
                publisher,
                BOOTSTRAP_USER);
    }

    /** env 임베딩 키가 비어 있는 서비스 — P1-b(유효 키 없는 재색인 거부) 검증용. */
    private SettingsService newServiceNoEnvEmbeddingKey(
            ModelSettingRepository repo,
            EmbeddingClientFactory factory,
            ApplicationEventPublisher publisher)
            throws Exception {
        return new SettingsService(
                repo,
                enabledCipher(),
                new EmbeddingProperties("voyage", "", null, null, 1024),
                new LlmProperties("anthropic", "", null, null, 4096),
                factory,
                realCatalog(),
                publisher,
                BOOTSTRAP_USER);
    }

    private ModelSetting seedRow() {
        ModelSetting s = new ModelSetting();
        s.setChatProvider("anthropic");
        s.setChatModel("claude-opus-4-8");
        s.setEmbeddingProvider("voyage");
        s.setEmbeddingModel("voyage-3");
        s.setEmbeddingStatus("READY");
        return s;
    }

    @Test
    void probeFailureRejectsSaveAndNoStatusChange() {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        // factory 가 예외 던지는 임베딩 클라이언트를 반환하도록 구성
        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient bad = mock(EmbeddingClient.class);
        when(bad.embedDocument(anyString())).thenThrow(new RuntimeException("401 unauthorized"));
        when(factory.forSettings(any())).thenReturn(bad);

        SettingsService svc =
                new SettingsService(
                        repo,
                        new SecretCipher(""),
                        new EmbeddingProperties("voyage", "sk-env-key", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096),
                        factory,
                        realCatalog(),
                        mock(ApplicationEventPublisher.class),
                        BOOTSTRAP_USER);

        assertThrows(
                EmbeddingProbeException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null,
                                        null,
                                        null,
                                        null,
                                        "openai",
                                        "text-embedding-3-small",
                                        null,
                                        null))); // 임베딩 provider 변경 → 프로브 실패

        // 프로브가 실제로 호출됐는지 확인
        verify(factory).forSettings(any());
        verify(bad).embedDocument("probe");

        // 프로브 실패로 롤백되므로 상태는 그대로다(별도 경로에서만 상태 전이).
        assertEquals("READY", seed.getEmbeddingStatus());
    }

    @Test
    void embeddingKeyOnlyChangeProbesButDoesNotReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        // provider/model 은 그대로(null), 임베딩 키만 회전.
        UpdateResult result =
                svc.update(
                        new SettingsUpdate(null, null, null, null, null, null, "sk-rotated", null));

        // 키만 바뀌어도 새 키가 유효한지 프로브는 반드시 돈다.
        verify(factory).forSettings(any());
        verify(good).embedDocument("probe");

        // provider/model 이 안 바뀌었으니 기존 벡터는 유효 — 재색인은 트리거하지 않는다.
        verify(publisher, never()).publishEvent(any());
        assertFalse(result.embeddingChanged());
        assertEquals("READY", seed.getEmbeddingStatus());
    }

    @Test
    void embeddingProviderChangeProbesAndTriggersReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        UpdateResult result =
                svc.update(
                        new SettingsUpdate(
                                null,
                                null,
                                null,
                                null,
                                "openai",
                                "text-embedding-3-small",
                                null,
                                null));

        verify(factory).forSettings(any());
        verify(good).embedDocument("probe");
        verify(publisher).publishEvent(any(EmbeddingModelChangedEvent.class));
        assertTrue(result.embeddingChanged());
        assertEquals("REINDEXING", seed.getEmbeddingStatus());
    }

    @Test
    void probeFailureFromHttpErrorReportsStatusOnlyNotBody() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        // provider가 HTTP 401을 던지되, (일부 런타임/버전에서는) 예외 메시지 자체에 응답 바디·키가
        // 실릴 수 있다고 가정 — 이 바디/메시지가 클라이언트로 그대로 흘러나가면 안 되고, 상태코드
        // +상태문구만 담아야 한다.
        String fakeKey = "AIzaFAKEKEY123456";
        byte[] body =
                ("{\"error\":{\"message\":\"API key not valid: " + fakeKey + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        String rawMessageWithBody =
                "401 Unauthorized: [{\"error\":{\"message\":\"API key not valid: "
                        + fakeKey
                        + "\"}}]";
        RestClientResponseException httpError =
                new RestClientResponseException(
                        rawMessageWithBody,
                        HttpStatus.UNAUTHORIZED,
                        "Unauthorized",
                        null,
                        body,
                        StandardCharsets.UTF_8);

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient bad = mock(EmbeddingClient.class);
        when(bad.embedDocument(anyString())).thenThrow(httpError);
        when(factory.forSettings(any())).thenReturn(bad);

        SettingsService svc = newService(repo, factory, mock(ApplicationEventPublisher.class));

        EmbeddingProbeException ex =
                assertThrows(
                        EmbeddingProbeException.class,
                        () ->
                                svc.update(
                                        new SettingsUpdate(
                                                null,
                                                null,
                                                null,
                                                null,
                                                "openai",
                                                "text-embedding-3-small",
                                                null,
                                                null)));

        assertFalse(ex.getMessage().contains(fakeKey));
        assertFalse(ex.getMessage().contains(httpError.getResponseBodyAsString()));
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void probeFailureFromGenericExceptionMasksKeyInMessage() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        // HTTP 상태 예외가 아닌 저수준 예외(예: RestClient가 요청 URI를 메시지에 그대로 담는 IO
        // 오류)의 메시지에 키가 섞여 나오는 경우도 방어적으로 마스킹돼야 한다.
        String fakeUrl =
                "https://generativelanguage.googleapis.com/v1beta/models/x:embedContent?key=AIzaFAKEKEY123456";
        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient bad = mock(EmbeddingClient.class);
        when(bad.embedDocument(anyString()))
                .thenThrow(
                        new RuntimeException("I/O error on POST request for \"" + fakeUrl + "\""));
        when(factory.forSettings(any())).thenReturn(bad);

        SettingsService svc = newService(repo, factory, mock(ApplicationEventPublisher.class));

        EmbeddingProbeException ex =
                assertThrows(
                        EmbeddingProbeException.class,
                        () ->
                                svc.update(
                                        new SettingsUpdate(
                                                null,
                                                null,
                                                null,
                                                null,
                                                "openai",
                                                "text-embedding-3-small",
                                                null,
                                                null)));

        assertFalse(ex.getMessage().contains("AIzaFAKEKEY123456"));
    }

    @Test
    void chatOnlyChangeSkipsProbeAndReindex() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        // provider 교체는 P1-d 로 새 키를 요구하므로 키를 함께 전달 — 그래도 임베딩 프로브/재색인은 없다.
        UpdateResult result =
                svc.update(
                        new SettingsUpdate(
                                "openai", "gpt-4.1", "sk-chat-new", null, null, null, null, null));

        verify(factory, never()).forSettings(any());
        verify(publisher, never()).publishEvent(any());
        assertFalse(result.embeddingChanged());
        assertEquals("READY", seed.getEmbeddingStatus());
    }

    // ── P1-d: chat provider 변경은 새 키를 요구하고 모델을 자동 기본값으로 맞춘다 ──

    @Test
    void chatProviderChangeWithoutKeyIsRejected() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        // 옛 키는 옛 provider(anthropic) 것 — provider 만 openai 로 바꾸면 400.
        assertThrows(
                BadRequestException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        "openai", null, null, null, null, null, null, null)));

        // 아무 것도 저장/발행되지 않는다(프로브도 없음).
        verify(factory, never()).forSettings(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void chatProviderChangeWithoutModelUsesProviderDefault() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        UpdateResult result =
                svc.update(
                        new SettingsUpdate(
                                "openai", null, "sk-chat-new", null, null, null, null, null));

        // 모델 미지정 → 새 provider(openai)의 기본 모델로 자동 설정.
        assertEquals("openai", seed.getChatProvider());
        assertEquals("gpt-4.1", seed.getChatModel());

        // chat 은 프로브·재색인이 없다.
        verify(factory, never()).forSettings(any());
        verify(publisher, never()).publishEvent(any());
        assertFalse(result.embeddingChanged());
    }

    @Test
    void chatProviderChangeWithModelUsesGivenModel() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        svc.update(
                new SettingsUpdate(
                        "openai", "gpt-4.1-mini", "sk-chat-new", null, null, null, null, null));

        // 사용자가 준 모델이 기본값을 이긴다.
        assertEquals("openai", seed.getChatProvider());
        assertEquals("gpt-4.1-mini", seed.getChatModel());
    }

    // ── P1-b: 유효 키 없는 임베딩 provider/모델 변경은 기존 벡터를 지키기 위해 거부한다 ──

    @Test
    void embeddingChangeWithoutValidKeyIsRejectedAndVectorsProtected() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        // env 임베딩 키가 비어 있고 요청에도 키가 없음 → 유효 키 없음.
        SettingsService svc = newServiceNoEnvEmbeddingKey(repo, factory, publisher);

        assertThrows(
                BadRequestException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null,
                                        null,
                                        null,
                                        null,
                                        "openai",
                                        "text-embedding-3-small",
                                        null,
                                        null)));

        // 파괴 경로(Stub 폴백 프로브 → 재색인)에 진입하지 않는다: 프로브 팩토리 미호출·미발행.
        verify(factory, never()).forSettings(any());
        verify(publisher, never()).publishEvent(any());
        assertEquals("READY", seed.getEmbeddingStatus());
    }

    @Test
    void embeddingProviderChangeWithKeyNoModelUsesDefaultAndReindexes() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        // provider 만 openai 로 바꾸고 유효 키를 함께 전달, 모델은 미지정.
        UpdateResult result =
                svc.update(
                        new SettingsUpdate(
                                null, null, null, null, "openai", null, "sk-emb-new", null));

        // 모델 미지정 → 새 provider(openai)의 기본 임베딩 모델로 자동 설정(프로브가 정합 검증).
        assertEquals("openai", seed.getEmbeddingProvider());
        assertEquals("text-embedding-3-small", seed.getEmbeddingModel());

        verify(factory).forSettings(any());
        verify(good).embedDocument("probe");
        verify(publisher).publishEvent(any(EmbeddingModelChangedEvent.class));
        assertTrue(result.embeddingChanged());
        assertEquals("REINDEXING", seed.getEmbeddingStatus());
    }

    // ── base-url https 스킴 검증 ──

    @Test
    void nonHttpsChatBaseUrlIsRejected() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        SettingsService svc =
                newService(
                        repo,
                        mock(EmbeddingClientFactory.class),
                        mock(ApplicationEventPublisher.class));

        assertThrows(
                BadRequestException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null,
                                        null,
                                        null,
                                        "http://insecure",
                                        null,
                                        null,
                                        null,
                                        null)));
    }

    @Test
    void nonHttpsEmbeddingBaseUrlIsRejected() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        SettingsService svc =
                newService(
                        repo,
                        mock(EmbeddingClientFactory.class),
                        mock(ApplicationEventPublisher.class));

        assertThrows(
                BadRequestException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "http://insecure")));
    }

    // ── 재색인 세대(generation) 토큰 (P1-c) ──

    @Test
    void reindexTriggeringChangeIncrementsGenerationAndPublishesIt() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        seed.setEmbeddingGeneration(4L);
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient good = mock(EmbeddingClient.class);
        when(good.embedDocument(anyString())).thenReturn(new float[1024]);
        when(factory.forSettings(any())).thenReturn(good);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, factory, publisher);

        svc.update(new SettingsUpdate(null, null, null, null, "openai", null, "sk-emb-new", null));

        // 세대는 1 증가하고, 발행된 이벤트가 그 새 값을 실어야 한다(gen + REINDEXING 동시 커밋).
        assertEquals(5L, seed.getEmbeddingGeneration());
        ArgumentCaptor<EmbeddingModelChangedEvent> captor =
                ArgumentCaptor.forClass(EmbeddingModelChangedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(5L, captor.getValue().generation());
        assertEquals(1L, captor.getValue().userId(), "이벤트는 변경을 요청한 사용자(currentUser)를 실어야 한다");
        assertEquals("REINDEXING", seed.getEmbeddingStatus());
    }

    @Test
    void nonReindexChangeDoesNotTouchGenerationOrPublish() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting seed = seedRow();
        seed.setEmbeddingGeneration(4L);
        when(repo.findByUserId(1L)).thenReturn(Optional.of(seed));

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SettingsService svc = newService(repo, mock(EmbeddingClientFactory.class), publisher);

        // chat base-url 만 변경 — 임베딩 재색인과 무관.
        svc.update(new SettingsUpdate(null, null, null, "https://db-chat", null, null, null, null));

        assertEquals(4L, seed.getEmbeddingGeneration());
        verify(publisher, never()).publishEvent(any());
        assertEquals("READY", seed.getEmbeddingStatus());
    }
}
