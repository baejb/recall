package com.recall.common.config;

import com.recall.auth.config.ApiErrorAuthenticationHandlers;
import com.recall.auth.service.LoginNotAllowedException;
import com.recall.auth.service.RecallOidcUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * 두 가지 모드가 있고, <b>Spring 프로필 {@code oauth} 하나가</b> 어느 쪽인지 정한다.
 *
 * <ul>
 *   <li><b>{@code oauth} 프로필</b> — Google 로그인 + 세션 + CSRF. 실제 멀티유저 동작.
 *   <li><b>그 외(기본)</b> — 부트스트랩 모드: 전 요청 permitAll, 모든 데이터가 {@code app_user.id=1} 소유. 로컬 개발·테스트가 로그인
 *       없이 돌아가야 하므로 남긴다.
 * </ul>
 *
 * <p><b>스위치를 하나로 둔 이유</b> — 프로필과 속성 두 개로 나누면 "로그인은 켜졌는데 소유자 해석은 부트스트랩(항상 id=1)"인 조합이 만들어질 수 있다. 그건
 * 로그인한 사용자가 남의 데이터를 보는 상태이고 화면상으로는 정상으로 보인다(🔴 교차유출). 그래서 {@code oauth} 프로필이 로그인·소유자 해석·CSRF 를
 * <b>함께</b> 켠다({@code SecurityContextCurrentUserProvider} 와 {@code BootstrapCurrentUserProvider} 가
 * 같은 프로필 조건으로 갈린다).
 *
 * <p>부트스트랩 모드는 <b>인터넷에 노출하면 안 된다</b>. 그 사실을 부팅 로그와 {@code /api/me} 응답({@code bootstrapMode})으로 드러낸다
 * — 숨기면 열린 인스턴스가 정상처럼 보인다.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** SPA 껍데기·정적 자원·헬스체크·로그인 시작 경로. 인증 없이 열려야 로그인 화면 자체를 띄울 수 있다. */
    private static final String[] PUBLIC_PATHS = {
        "/", "/index.html", "/assets/**", "/favicon.ico", "/api/health", "/oauth2/**", "/login/**"
    };

    /**
     * OAuth 모드 — 로그인 필요 + 세션 + CSRF.
     *
     * <p><b>CSRF 를 다시 켠다</b>: 세션 쿠키로 인증하는 순간 상태변경 POST(capture 저장·검토 승인/반려·설정 변경)가 CSRF 표적이 된다.
     * 부트스트랩 모드에서 disable 했던 것은 인증이 없어 훔칠 세션도 없었기 때문이고, 세션이 생기면 그 전제가 사라진다.
     *
     * <p>토큰은 {@code XSRF-TOKEN} 쿠키로 내려보내고(httpOnly=false — SPA 가 읽어야 한다) 프론트가 {@code X-XSRF-TOKEN}
     * 헤더로 돌려준다. {@link CsrfTokenRequestAttributeHandler} 를 쓰는 이유: 기본 핸들러는 BREACH 방어를 위해 토큰을 요청마다
     * 다르게 인코딩해 쿠키 값과 헤더 값이 어긋난다 — SPA 가 쿠키를 읽어 헤더로 보내는 방식에서는 이 핸들러여야 값이 맞는다.
     *
     * <p>API 경로의 인증 실패는 리다이렉트가 아니라 401·403 JSON 이다({@link ApiErrorAuthenticationHandlers}).
     */
    @Bean
    @Profile("oauth")
    SecurityFilterChain oauthSecurityFilterChain(
            HttpSecurity http, RecallOidcUserService oidcUserService) throws Exception {
        log.info("보안 모드: oauth — Google 로그인 + 세션 + CSRF");

        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http.csrf(
                        csrf ->
                                csrf.csrfTokenRepository(
                                                CookieCsrfTokenRepository.withHttpOnlyFalse())
                                        .csrfTokenRequestHandler(csrfRequestHandler))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(
                        ex ->
                                ex.defaultAuthenticationEntryPointFor(
                                                ApiErrorAuthenticationHandlers.entryPoint(),
                                                PathPatternRequestMatcher.withDefaults()
                                                        .matcher("/api/**"))
                                        .accessDeniedHandler(
                                                ApiErrorAuthenticationHandlers
                                                        .accessDeniedHandler()))
                .oauth2Login(
                        oauth ->
                                oauth.userInfoEndpoint(
                                                userInfo ->
                                                        userInfo.oidcUserService(oidcUserService))
                                        // 로그인 성공 후 SPA 로 돌린다. 화면이 /api/me 로 세션을 확인한다.
                                        .defaultSuccessUrl("/", true)
                                        // 허용목록 거절은 결함이 아니라 정책이다 — 이유를 쿼리로 알리고 화면으로
                                        // 되돌린다. 실패 URL 을 주지 않으면 스프링 기본 흰 페이지가 나와 사용자가
                                        // 원인을 알 수 없다(조용한 실패에 가깝다).
                                        .failureHandler(
                                                (request, response, exception) -> {
                                                    boolean notAllowed =
                                                            exception
                                                                    instanceof
                                                                    LoginNotAllowedException;
                                                    log.warn(
                                                            "로그인 실패({}): {}",
                                                            notAllowed ? "허용목록 밖" : "인증 오류",
                                                            exception.getMessage());
                                                    response.sendRedirect(
                                                            notAllowed
                                                                    ? "/?login_error=not_allowed"
                                                                    : "/?login_error=failed");
                                                }))
                // 로그아웃은 AuthController 가 공통 응답 형식으로 처리한다(프론트가 /api 만 프록시한다).
                .logout(logout -> logout.disable());

        return http.build();
    }

    /**
     * 부트스트랩 모드(기본) — 인증 없이 전 요청 허용, 모든 데이터가 {@code app_user.id=1} 소유.
     *
     * <p>남겨 두는 이유: 로컬 개발과 테스트가 Google client-id 없이 돌아가야 한다. 대신 <b>부팅 로그로 요란하게 알린다</b> — 이 모드가 배포로
     * 새어 나가면 인스턴스가 열린 상태이고, 그때 조용하면 아무도 모른다.
     *
     * <p>CSRF 를 끄는 것도 이 모드에서만이다: 훔칠 세션이 없으므로 CSRF 방어가 지킬 것이 없고, 켜 두면 토큰 없이 호출하는 개발·테스트가 전부 403 이
     * 된다.
     */
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
