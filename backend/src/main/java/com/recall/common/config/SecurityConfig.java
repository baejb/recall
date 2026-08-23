package com.recall.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 멀티유저 SSO 로그인 — 틀(stub)만 배선.
 *
 * <p>oauth2-client 의존성이 들어오면서 Spring Security 가 클래스패스에 올라오면 기본값으로 전 요청이 잠긴다. 실제 인증/인가 로직은 후속(팀원)이
 * 채우므로, 그 전까지 기존 엔드포인트 동작을 깨지 않도록 전 요청을 permitAll 로 연다.
 *
 * <p>TODO(멀티유저 인증 — 팀원):
 *
 * <ul>
 *   <li>{@code .oauth2Login()} 으로 Google 로그인 배선(application.yml 의 registration.google 주석 해제).
 *   <li>인증된 principal 의 OAuth {@code sub} 로 {@code app_user} 를 (provider, subject) 조회/생성.
 *   <li>요청 컨텍스트에 현재 사용자 {@code user_id} 를 주입 → 리포지토리/파이프라인이 user_id 로 스코프.
 *   <li>authorizeHttpRequests 를 실제 정책(로그인 필요/공개 경로 구분)으로 교체.
 *   <li>CSRF 재검토 — 지금은 토큰 없는 stub 이라 disable 했지만, 세션 기반 oauth2Login 이 붙으면 상태변경 POST(capture·review
 *       승인/반려·memory 전이)가 CSRF 표적이 된다. 로그인 배선과 동시에 CSRF 를 다시 켜고 stateless/SSE 엔드포인트만 예외 처리할 것.
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
