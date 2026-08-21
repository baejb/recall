package com.recall.common;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OAuth 인증이 배선되기 전까지 쓰는 기본 {@link CurrentUserProvider} — 항상 부트스트랩 사용자(1)를 반환한다. 단일 사용자 시절 동작과 동일하게,
 * 모든 요청이 app_user(id=1)의 데이터로 스코프된다.
 *
 * <p>후속(팀원) 핸드오프: SecurityContext 기반 {@link CurrentUserProvider} 를 등록하고 {@code
 * recall.auth.provider=oauth} 로 두면 이 기본 구현은 물러난다. 교체는 두 안전장치로 결정적이다 — property 가드(빈 정의 순서와 무관)와
 * {@link ConditionalOnMissingBean}(팀원 빈이 있으면 자동 후퇴).
 */
@Configuration
public class BootstrapCurrentUserProvider {

    /** V11 마이그레이션이 시드하는 부트스트랩 사용자 id. app_user.id=1 (V11) 과 반드시 동기 유지 — 부팅 시 검증한다. */
    public static final long BOOTSTRAP_USER_ID = 1L;

    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    @ConditionalOnProperty(
            prefix = "recall.auth",
            name = "provider",
            havingValue = "bootstrap",
            matchIfMissing = true)
    CurrentUserProvider currentUserProvider() {
        return () -> BOOTSTRAP_USER_ID;
    }

    /**
     * 부트스트랩 모드 부팅 시 V11 시드(app_user.id=1)와 {@link #BOOTSTRAP_USER_ID} 가 어긋나지 않았는지 fail-fast 검증한다.
     * 어긋나면 모든 요청이 존재하지 않는 사용자로 스코프돼 조용히 실패하므로(조용한 실패 금지), 부팅을 막는다.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "recall.auth",
            name = "provider",
            havingValue = "bootstrap",
            matchIfMissing = true)
    ApplicationRunner bootstrapUserCheck(JdbcTemplate jdbc) {
        return args -> {
            Integer count =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM app_user WHERE id = ?",
                            Integer.class,
                            BOOTSTRAP_USER_ID);
            if (count == null || count == 0) {
                throw new IllegalStateException(
                        "부트스트랩 사용자(app_user.id="
                                + BOOTSTRAP_USER_ID
                                + ")가 없다 — V11 시드와 BOOTSTRAP_USER_ID 가 어긋났다");
            }
        };
    }
}
