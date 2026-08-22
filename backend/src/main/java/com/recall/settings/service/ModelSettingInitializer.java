package com.recall.settings.service;

import com.recall.common.config.BootstrapCurrentUserProvider;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.repository.ModelSettingRepository;
import com.recall.settings.service.entity.ModelSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 부팅 1회 env→DB 시드. model_setting 도입 전의 env 기반 배포는 provider/model/base-url 을 env 로 공급했는데, 도입 후 이
 * 값들이 DB 행(anthropic/voyage 로 시드)에서만 읽혀 배포가 깨졌다. 이를 복구하기 위해 {@code configured=false} 인 행을 env 값으로
 * 1회 채운다.
 *
 * <p>🔴 키 컬럼({@code *_api_key_enc})은 건드리지 않는다 — 키는 env 에 남고 {@link SettingsService}의 복호화 폴백이 env 키를
 * 쓴다. 덕분에 {@code RECALL_SECRET_KEY} 없이도 시드가 동작한다. 시드 후 {@code configured=true}로 잠가 이후 UI 편집을 덮어쓰지
 * 않는다.
 *
 * <p>🔴 부트스트랩({@link BootstrapCurrentUserProvider#BOOTSTRAP_USER_ID}) 행만 시드한다 — 다른 사용자의
 * model_setting 행은 절대 만들거나 건드리지 않는다. 부트스트랩 행이 없으면 시드를 건너뛸 뿐, 대신 다른 사용자 행을 만들지 않는다.
 */
@Component
public class ModelSettingInitializer {

    private static final Logger log = LoggerFactory.getLogger(ModelSettingInitializer.class);

    private final ModelSettingRepository repository;
    private final LlmProperties envChat;
    private final EmbeddingProperties envEmbedding;

    public ModelSettingInitializer(
            ModelSettingRepository repository,
            LlmProperties envChat,
            EmbeddingProperties envEmbedding) {
        this.repository = repository;
        this.envChat = envChat;
        this.envEmbedding = envEmbedding;
    }

    /**
     * 컨텍스트 준비 완료 시점에 실행. {@code @EventListener} 메서드는 컨테이너 프록시로 호출되므로 {@code @Transactional}이 적용된다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedFromEnvIfNeeded() {
        ModelSetting s =
                repository
                        .findByUserId(BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID)
                        .orElse(null);
        if (s == null) {
            log.warn(
                    "model_setting(user_id={}) 미존재 — env 시드를 건너뛴다",
                    BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID);
            return;
        }
        if (s.isConfigured()) {
            return;
        }

        s.setChatProvider(envChat.provider());
        s.setChatModel(envChat.model());
        s.setChatBaseUrl(envChat.baseUrl());
        s.setEmbeddingProvider(envEmbedding.provider());
        s.setEmbeddingModel(envEmbedding.model());
        s.setEmbeddingBaseUrl(envEmbedding.baseUrl());
        s.setConfigured(true);
        repository.save(s);

        // 비밀 없이 provider/model 만 로그(키·base-url 값도 굳이 남기지 않는다).
        log.info(
                "model_setting env 시드 완료 — chat provider={}, embedding provider={} (키는 env 유지)",
                s.getChatProvider(),
                s.getEmbeddingProvider());
    }
}
