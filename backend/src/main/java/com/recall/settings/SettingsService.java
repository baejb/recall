package com.recall.settings;

import com.recall.common.BadRequestException;
import com.recall.common.BootstrapCurrentUserProvider;
import com.recall.common.CurrentUserProvider;
import com.recall.common.SecretCipher;
import com.recall.common.SecretMasking;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.ProviderCatalog.Role;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

/**
 * 모델 설정의 사용자별 조회·변경. 키는 복호화해 클라이언트에 넘기고, 저장 시 암호화한다.
 *
 * <p>env 키 폴백은 **부트스트랩 사용자({@link BootstrapCurrentUserProvider#BOOTSTRAP_USER_ID})에게만** 적용된다 — 다른
 * 사용자는 DB 에 자신의 암호화된 키가 없으면 미설정으로 취급한다(스펙 §7, env 키가 전 사용자에게 새면 🔴 치명).
 *
 * <p>파라미터 없는 진입점(예: {@link #currentChat()}, {@link #update(SettingsUpdate)})은 {@link
 * CurrentUserProvider}로 현재 요청 사용자를 해석해 사용자별 로직에 위임한다 — {@code SettingsBackedLlmClient}/{@code
 * EmbeddingClient}, {@code ReindexService}가 아직 이 진입점을 호출하므로 제거하지 않는다(후속 태스크에서 사용자별 호출로 이전).
 */
@Service
public class SettingsService {

    private final ModelSettingRepository repository;
    private final SecretCipher cipher;
    private final EmbeddingProperties envEmbedding;
    private final LlmProperties envChat;
    private final EmbeddingClientFactory embeddingFactory;
    private final ProviderCatalog catalog;
    private final ApplicationEventPublisher publisher;
    private final CurrentUserProvider currentUser;

    public SettingsService(
            ModelSettingRepository repository,
            SecretCipher cipher,
            EmbeddingProperties envEmbedding,
            LlmProperties envChat,
            EmbeddingClientFactory embeddingFactory,
            ProviderCatalog catalog,
            ApplicationEventPublisher publisher,
            CurrentUserProvider currentUser) {
        this.repository = repository;
        this.cipher = cipher;
        this.envEmbedding = envEmbedding;
        this.envChat = envChat;
        this.embeddingFactory = embeddingFactory;
        this.catalog = catalog;
        this.publisher = publisher;
        this.currentUser = currentUser;
    }

    private ModelSetting row(long userId) {
        return repository
                .findByUserId(userId)
                .orElseThrow(
                        () -> new IllegalStateException("model_setting 미초기화(user=" + userId + ")"));
    }

    @Transactional(readOnly = true)
    public EmbeddingProperties currentEmbedding() {
        return embeddingFor(currentUser.currentUserId());
    }

    /** {@code userId} 소유 설정으로 {@link EmbeddingProperties}를 해석한다(키는 복호화, env 폴백은 부트스트랩만). */
    @Transactional(readOnly = true)
    public EmbeddingProperties embeddingFor(long userId) {
        return embeddingPropsFrom(userId, row(userId));
    }

    /** 행(row)의 현재 값으로 {@link EmbeddingProperties}를 구성한다(키는 복호화, 없으면 env 폴백 — 부트스트랩만). */
    private EmbeddingProperties embeddingPropsFrom(long userId, ModelSetting s) {
        String key = resolveEmbeddingKey(userId, s.getEmbeddingApiKeyEnc());
        String baseUrl = baseUrlOr(s.getEmbeddingBaseUrl(), envEmbedding.baseUrl());
        return new EmbeddingProperties(
                s.getEmbeddingProvider(), key, s.getEmbeddingModel(), baseUrl, 1024);
    }

    @Transactional(readOnly = true)
    public LlmProperties currentChat() {
        return chatFor(currentUser.currentUserId());
    }

    /** {@code userId} 소유 설정으로 {@link LlmProperties}를 해석한다(키는 복호화, env 폴백은 부트스트랩만). */
    @Transactional(readOnly = true)
    public LlmProperties chatFor(long userId) {
        ModelSetting s = row(userId);
        String key = resolveChatKey(userId, s.getChatApiKeyEnc());
        String baseUrl = baseUrlOr(s.getChatBaseUrl(), envChat.baseUrl());
        return new LlmProperties(
                s.getChatProvider(), key, s.getChatModel(), baseUrl, envChat.maxTokens());
    }

    /**
     * {@code userId}의 chat 이 사용 가능한 키를 갖는지(DB 키, 또는 부트스트랩이면 env 폴백). {@code userId} 소유의
     * model_setting 행이 아직 없으면(예: 막 가입해 설정을 한 번도 만진 적 없는 사용자) 암호문 없음과 동일하게 취급한다 — 그래도 env 폴백은 부트스트랩
     * 전용 규칙을 그대로 통과해야 한다(행 유무로 이 경계를 우회할 수 없다).
     */
    @Transactional(readOnly = true)
    public boolean isChatConfigured(long userId) {
        String enc =
                repository.findByUserId(userId).map(ModelSetting::getChatApiKeyEnc).orElse(null);
        return notBlank(resolveChatKey(userId, enc));
    }

    /**
     * {@code userId}의 embedding 이 사용 가능한 키를 갖는지 — {@link #isChatConfigured(long)}과 동일한 미존재 행 계약.
     */
    @Transactional(readOnly = true)
    public boolean isEmbeddingConfigured(long userId) {
        String enc =
                repository
                        .findByUserId(userId)
                        .map(ModelSetting::getEmbeddingApiKeyEnc)
                        .orElse(null);
        return notBlank(resolveEmbeddingKey(userId, enc));
    }

    /**
     * chat API 키를 해석한다 — DB 암호문({@code enc})이 있으면 복호화해 반환하고, 없으면 {@code userId}가 부트스트랩 사용자일 때만 env
     * 키로 폴백한다. 그 외 사용자는 암호문이 없으면 {@code null}(미설정)이다 — env 키가 전 사용자에게 새지 않도록 하는 경계(스펙 §7).
     * model_setting 행 자체가 없는 사용자도 {@code enc=null}로 이 규칙을 그대로 탄다.
     */
    private String resolveChatKey(long userId, String enc) {
        if (notBlank(enc)) return cipher.decrypt(enc);
        return userId == BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID ? envChat.apiKey() : null;
    }

    /** embedding API 키 해석 — {@link #resolveChatKey(long, String)}과 동일한 부트스트랩 전용 env 폴백 규칙. */
    private String resolveEmbeddingKey(long userId, String enc) {
        if (notBlank(enc)) return cipher.decrypt(enc);
        return userId == BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID
                ? envEmbedding.apiKey()
                : null;
    }

    @Transactional(readOnly = true)
    public String embeddingStatus() {
        return embeddingStatus(currentUser.currentUserId());
    }

    /**
     * {@code userId} 소유 model_setting 행의 embedding_status. {@link CurrentUserProvider}에 의존하지 않아야 하는
     * 호출부(SSE 답변 가상 스레드처럼 요청 스레드가 이미 신뢰한 userId를 클로저로 캡처해 도는 경우)는 이 오버로드로 명시적 userId를 넘긴다.
     */
    @Transactional(readOnly = true)
    public String embeddingStatus(long userId) {
        return row(userId).getEmbeddingStatus();
    }

    @Transactional
    public void setEmbeddingStatus(String status) {
        row(currentUser.currentUserId()).setEmbeddingStatus(status);
    }

    @Transactional
    public UpdateResult update(SettingsUpdate u) {
        long userId = currentUser.currentUserId();
        ModelSetting s = row(userId);
        boolean reindexNeeded = false;

        // base-url 오버라이드는 https 스킴만 허용(SSRF 여지 축소). null=변경 없음, ""=해제는 통과.
        requireHttpsOrBlank(u.chatBaseUrl());
        requireHttpsOrBlank(u.embeddingBaseUrl());

        // ── chat ──
        boolean chatProviderChanged =
                u.chatProvider() != null && !u.chatProvider().equals(s.getChatProvider());
        if (u.chatProvider() != null) {
            catalog.requireSupported(Role.CHAT, u.chatProvider());
            // P1-d: 옛 키는 옛 provider 것 — provider 교체 시 반드시 새 키를 받아야 한다.
            // 없으면 옛 키+새 provider 조합으로 나중에 캡처 추출 등에서 조용히 실패한다.
            if (chatProviderChanged && !notBlank(u.chatApiKey())) {
                throw new BadRequestException("chat provider 변경에는 새 API 키가 필요합니다");
            }
            s.setChatProvider(u.chatProvider());
        }
        // 사용자가 준 모델이 이긴다. 미지정 + provider 변경이면 새 provider 기본 모델로 자동 설정.
        if (u.chatModel() != null) {
            s.setChatModel(u.chatModel());
        } else if (chatProviderChanged) {
            s.setChatModel(defaultModel(catalog.chatModels(), u.chatProvider()));
        }
        if (notBlank(u.chatApiKey())) s.setChatApiKeyEnc(encrypt(u.chatApiKey()));
        // base-url 은 비밀이 아니다. null=변경 없음, ""=해제(null 로), 그 외=설정.
        if (u.chatBaseUrl() != null) s.setChatBaseUrl(blankToNull(u.chatBaseUrl()));

        // ── embedding ──
        boolean embeddingProviderChanged =
                u.embeddingProvider() != null
                        && !u.embeddingProvider().equals(s.getEmbeddingProvider());
        if (u.embeddingProvider() != null) {
            catalog.requireSupported(Role.EMBEDDING, u.embeddingProvider());
            reindexNeeded |= embeddingProviderChanged;
            s.setEmbeddingProvider(u.embeddingProvider());
        }
        // chat 과 대칭: 모델 미지정 + provider 변경이면 새 provider 기본 임베딩 모델로 자동 설정
        // (프로브가 provider+model 정합을 검증하도록).
        if (u.embeddingModel() != null) {
            reindexNeeded |= !u.embeddingModel().equals(s.getEmbeddingModel());
            s.setEmbeddingModel(u.embeddingModel());
        } else if (embeddingProviderChanged) {
            s.setEmbeddingModel(defaultModel(catalog.embeddingModels(), u.embeddingProvider()));
        }
        boolean embeddingKeyRotated = notBlank(u.embeddingApiKey());
        if (embeddingKeyRotated) s.setEmbeddingApiKeyEnc(encrypt(u.embeddingApiKey()));
        if (u.embeddingBaseUrl() != null) s.setEmbeddingBaseUrl(blankToNull(u.embeddingBaseUrl()));

        // P1-b: 재색인은 기존 memory_embedding 을 전량 재작성한다. 유효 키가 없으면 팩토리가
        // StubEmbeddingClient 를 반환해 프로브가 무의미하게 통과하고, 재색인이 기존 벡터를 0-벡터로
        // 덮어써 검색이 조용히 망가진다. 재색인 전에 유효 임베딩 키를 강제해 그 경로를 막는다.
        // (probe/REINDEXING/publish 이전이므로 @Transactional 이 롤백되어 아무 것도 파괴되지 않는다.)
        if (reindexNeeded) {
            String effectiveKey = resolveEmbeddingKey(userId, s.getEmbeddingApiKeyEnc());
            if (!notBlank(effectiveKey)) {
                throw new BadRequestException("임베딩 provider/모델 변경에는 유효한 API 키가 필요합니다 (기존 벡터 보호)");
            }
        }

        // 검증(probe)과 재색인은 별개다: provider/model 이 바뀌면 벡터 공간 자체가 달라져 재색인이
        // 필요하지만, 키만 회전(같은 provider+model)해도 오·타이핑된 키가 그대로 저장돼 나중에야
        // 호출 시점에 실패하지 않도록 새 키로 프로브는 반드시 돌려야 한다(기존 벡터는 유효하게 남음).
        boolean validationNeeded = reindexNeeded || embeddingKeyRotated;
        if (validationNeeded) {
            probeEmbedding(embeddingPropsFrom(userId, s));
        }

        if (reindexNeeded) {
            // 프로브 성공 후에만 재색인 트리거. 세대(generation)를 1 증가시켜 REINDEXING 과 함께 이 트랜잭션에
            // 커밋한다(gen + REINDEXING 동시 커밋). 재색인은 AFTER_COMMIT 이벤트 수신자(ReindexService)가
            // 배경에서 수행하며, 이 세대 토큰을 들고 돌아 뒤늦은 앞선 잡의 상태 덮어쓰기를 막는다(순환 회피).
            long gen = s.getEmbeddingGeneration() + 1;
            s.setEmbeddingGeneration(gen);
            s.setEmbeddingStatus("REINDEXING");
            publisher.publishEvent(new EmbeddingModelChangedEvent(gen));
        }

        return new UpdateResult(reindexNeeded);
    }

    /**
     * 저장 전 프로브(test-before-save) — 후보 임베딩 설정으로 실제 임베딩 1회를 시도해 유효성을 확인한다. 실패 시(잘못된 키/모델 등) {@link
     * EmbeddingProbeException}을 던져 트랜잭션을 롤백시킨다. 키 값·provider 원문 응답 바디는 클라이언트 400 응답(detail)으로 그대로
     * 나가지 않는다: HTTP 상태 예외는 상태코드+상태문구만 담고, 그 외는 {@link SecretMasking#mask(String)}으로 방어적 마스킹한 뒤 담는다.
     */
    private void probeEmbedding(EmbeddingProperties candidate) {
        try {
            embeddingFactory.forSettings(candidate).embedDocument("probe");
        } catch (EmbeddingProbeException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new EmbeddingProbeException(
                    "임베딩 설정 검증 실패(키·모델·base URL 확인): HTTP "
                            + e.getStatusCode().value()
                            + " "
                            + e.getStatusText());
        } catch (Exception e) {
            throw new EmbeddingProbeException(
                    SecretMasking.mask("임베딩 설정 검증 실패(키·모델·base URL 확인): " + e.getMessage()));
        }
    }

    private String encrypt(String plaintext) {
        if (!cipher.isEnabled()) {
            throw new IllegalStateException(
                    "RECALL_SECRET_KEY 미설정 — UI 입력 키를 저장할 수 없다(fail-closed)");
        }
        return cipher.encrypt(plaintext);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** 비어 있지 않은 base-url 은 https 스킴만 허용한다(null=변경 없음, ""=해제는 통과). */
    private static void requireHttpsOrBlank(String baseUrl) {
        if (notBlank(baseUrl) && !baseUrl.startsWith("https://")) {
            throw new BadRequestException("base URL 은 https 스킴만 허용됩니다");
        }
    }

    /**
     * provider 의 추천 모델 목록 첫 항목(=기본 모델). provider 는 requireSupported 로 검증돼 항목이 존재한다.
     *
     * <p>{@link ProviderCatalog}가 provider 키를 소문자로 색인하므로 여기서도 {@code toLowerCase()}로 조회한다(대소문자
     * 혼용이지만 지원되는 provider 에서 잠재 NPE 방지).
     */
    private static String defaultModel(Map<String, List<String>> models, String provider) {
        return models.get(provider.toLowerCase()).get(0);
    }

    /** DB 값이 있으면 그대로, 없으면(공백/널) env 폴백. 둘 다 공백이면 널이 되어 클라이언트가 provider 기본 URL 을 쓴다. */
    private static String baseUrlOr(String dbVal, String envVal) {
        return notBlank(dbVal) ? dbVal : envVal;
    }

    private static String blankToNull(String v) {
        return notBlank(v) ? v : null;
    }

    public record SettingsUpdate(
            String chatProvider,
            String chatModel,
            String chatApiKey,
            String chatBaseUrl,
            String embeddingProvider,
            String embeddingModel,
            String embeddingApiKey,
            String embeddingBaseUrl) {}

    /**
     * @param embeddingChanged 재색인이 트리거됨(REINDEXING 전이 발생) — 임베딩 키만 회전한 경우는 false.
     */
    public record UpdateResult(boolean embeddingChanged) {}
}
