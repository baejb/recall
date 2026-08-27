package com.recall.common.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 부트스트랩 모드의 {@link CurrentUserProvider} — 항상 부트스트랩 사용자(1)를 반환한다. 인증이 없는 로컬 개발·테스트에서 모든 요청이
 * app_user(id=1)의 데이터로 스코프된다.
 *
 * <p><b>{@code oauth} 프로필이면 물러난다</b> — 그때는 {@code SecurityContextCurrentUserProvider} 가 세션의
 * principal 에서 소유자를 해석한다. 조건을 <b>프로필 하나로</b> 통일한 이유: 전에는 {@code recall.auth.provider} 속성으로 갈렸는데,
 * 로그인은 켜고 이 속성만 그대로 둔 조합이 만들어질 수 있었다. 그 상태는 <b>로그인한 사용자가 남의 데이터를 보는</b> 것이고 화면상으로는 정상으로 보인다(🔴
 * 교차유출). 스위치가 하나면 그 조합 자체가 존재하지 않는다.
 */
@Configuration
@Profile("!oauth")
public class BootstrapCurrentUserProvider {

    /** V11 마이그레이션이 시드하는 부트스트랩 사용자 id. app_user.id=1 (V11) 과 반드시 동기 유지 — 부팅 시 검증한다. */
    public static final long BOOTSTRAP_USER_ID = 1L;

    @Bean
    CurrentUserProvider currentUserProvider() {
        return () -> BOOTSTRAP_USER_ID;
    }

    /**
     * 부트스트랩 모드 부팅 시 V11 시드(app_user.id=1)와 {@link #BOOTSTRAP_USER_ID} 가 어긋나지 않았는지 fail-fast 검증한다.
     * 어긋나면 모든 요청이 존재하지 않는 사용자로 스코프돼 조용히 실패하므로(조용한 실패 금지), 부팅을 막는다.
     */
    @Bean
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
