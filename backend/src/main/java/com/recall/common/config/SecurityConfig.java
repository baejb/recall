package com.recall.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * <b>부트스트랩 모드</b>의 필터 체인 — 인증 없이 전 요청 허용, 모든 데이터가 {@code app_user.id=1} 소유.
 *
 * <p>보안 모드는 두 가지고 <b>Spring 프로필 {@code oauth} 하나가</b> 어느 쪽인지 정한다. 이 파일은 그 프로필이 <b>아닐 때</b>의 체인만 갖는다
 * — {@code oauth} 체인은 {@code auth} 모듈이 소유한다({@code auth/config/OAuthSecurityConfig}).
 *
 * <p><b>왜 나눴나</b> — 한 파일에 두 체인을 두면 {@code common} 이 {@code auth} 의 내부 구현(OIDC 사용자 서비스·로그인 예외·인증 실패
 * 핸들러)을 직접 import 해야 한다. 그건 "모듈 root 에는 다른 모듈이 import 하는 공개 계약만 둔다"는 규칙을 정면으로 넘는 것이고, 배선이 불가피해 보인다는
 * 이유로 넘기 시작하면 다음에 또 넘어도 아무 신호가 없다. 체인을 소유 모듈로 옮기면 그 import 자체가 사라진다.
 *
 * <p>부트스트랩 모드를 남겨 두는 이유: 로컬 개발과 테스트가 Google client-id 없이 돌아가야 한다. 대신 <b>명시적 opt-in 이 없으면 부팅을
 * 막는다</b>({@link BootstrapModeGuard}) — 이 모드가 배포로 새어 나가면 인스턴스가 열린 상태이고, 그때 로그 한 줄로는 아무도 모른다.
 *
 * <p>CSRF 를 끄는 것도 이 모드에서만이다: 훔칠 세션이 없으므로 CSRF 방어가 지킬 것이 없고, 켜 두면 토큰 없이 호출하는 개발·테스트가 전부 403 이 된다.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @Profile("!oauth")
    SecurityFilterChain bootstrapSecurityFilterChain(HttpSecurity http) throws Exception {
        log.warn(
                "보안 모드: bootstrap — 인증 없음, 모든 요청이 app_user.id=1 로 스코프된다."
                        + " 인터넷에 노출하지 말 것(로그인 활성화: SPRING_PROFILES_ACTIVE=oauth).");

        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
