package com.recall.auth.config;

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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * OAuth 모드의 필터 체인 — Google 로그인 + 세션 + CSRF. {@code auth} 모듈이 자기 배선을 소유한다({@code
 * common/config/SecurityConfig} 는 부트스트랩 체인만 갖는다).
 *
 * <p><b>스위치를 하나로 둔 이유</b> — 프로필과 속성 두 개로 나누면 "로그인은 켜졌는데 소유자 해석은 부트스트랩(항상 id=1)"인 조합이 만들어질 수 있다. 그건
 * 로그인한 사용자가 남의 데이터를 보는 상태이고 화면상으로는 정상으로 보인다(🔴 교차유출). 그래서 {@code oauth} 프로필이 로그인·소유자 해석·CSRF 를
 * <b>함께</b> 켠다({@link SecurityContextCurrentUserProvider} 와 {@code BootstrapCurrentUserProvider} 가
 * 같은 프로필 조건으로 갈린다).
 */
@Configuration
@Profile("oauth")
public class OAuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuthSecurityConfig.class);

    /** SPA 껍데기·정적 자원·헬스체크·로그인 시작/콜백 경로. 인증 없이 열려야 로그인 화면 자체를 띄울 수 있다. */
    private static final String[] PUBLIC_PATHS = {
        "/", "/index.html", "/assets/**", "/favicon.ico", "/api/health", "/oauth2/**", "/login/**"
    };

    /** 인증·인가 실패를 JSON 으로 답할 경로. 브라우저가 직접 여는 HTML 경로는 스프링 기본 동작에 맡긴다. */
    static final String API_PATHS = "/api/**";

    /**
     * <b>CSRF 를 다시 켠다</b>: 세션 쿠키로 인증하는 순간 상태변경 POST(capture 저장·검토 승인/반려·설정 변경)가 CSRF 표적이 된다. 부트스트랩
     * 모드에서 disable 했던 것은 인증이 없어 훔칠 세션도 없었기 때문이고, 세션이 생기면 그 전제가 사라진다.
     *
     * <p>토큰은 {@code XSRF-TOKEN} 쿠키로 내려보내고(httpOnly=false — SPA 가 읽어야 한다) 프론트가 {@code X-XSRF-TOKEN}
     * 헤더로 돌려준다. {@link CsrfTokenRequestAttributeHandler} 를 쓰는 이유: 기본 핸들러는 BREACH 방어를 위해 토큰을 요청마다
     * 다르게 인코딩해 쿠키 값과 헤더 값이 어긋난다 — SPA 가 쿠키를 읽어 헤더로 보내는 방식에서는 이 핸들러여야 값이 맞는다.
     *
     * <p><b>{@code setCsrfRequestAttributeName(null)} 이 쿠키 발급을 보장하는 유일한 장치다.</b> 이 호출이 지연 로딩을 끄므로
     * {@code CsrfFilter} 가 매 요청 토큰을 로드·저장하고, 그때 쿠키가 나간다. 전에는 {@code AuthController.me} 가 같은 목적으로
     * {@code CsrfToken} 파라미터를 받았는데, 같은 목적의 장치가 두 곳에 있고 서로를 몰랐다 — 이 줄을 지우는 쪽은 컨트롤러 파라미터를 믿고, 파라미터를
     * 지우는 쪽은 이 줄을 믿는다. 읽지도 반환하지도 않는 미사용 파라미터라 린터·리뷰에서 먼저 지워질 쪽은 후자였고, 둘 다 지워지면 첫 상태변경 POST 가 403 이
     * 되면서 원인이 "로그인 문제"처럼 보인다. 그래서 <b>장치를 이쪽 하나로 모으고</b> 파라미터를 없앴다. 이 줄을 지우면 {@code
     * CsrfCookieIssuedTest} 가 깨진다(주석이 아니라 테스트가 지킨다).
     *
     * <p>API 경로의 인증 실패는 리다이렉트가 아니라 401·403 JSON 이다({@link ApiErrorAuthenticationHandlers}).
     */
    @Bean
    SecurityFilterChain oauthSecurityFilterChain(
            HttpSecurity http,
            RecallOidcUserService oidcUserService,
            AllowedEmailsRecheckFilter allowedEmailsRecheck)
            throws Exception {
        log.info("보안 모드: oauth — Google 로그인 + 세션 + CSRF");

        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        PathPatternRequestMatcher apiPaths =
                PathPatternRequestMatcher.withDefaults().matcher(API_PATHS);

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
                                                apiPaths)
                                        // 🔴 accessDeniedHandler 는 경로를 받지 않아 전역이다. 스코프를 핸들러
                                        // 안에서 잡지 않으면 브라우저로 직접 여는 경로의 403(CSRF 실패가 대표)에서
                                        // 화면 대신 {"success":false,…} JSON 이 그대로 노출된다.
                                        .accessDeniedHandler(
                                                ApiErrorAuthenticationHandlers.accessDeniedHandler(
                                                        apiPaths)))
                .oauth2Login(
                        oauth ->
                                oauth.userInfoEndpoint(
                                                userInfo ->
                                                        userInfo.oidcUserService(oidcUserService))
                                        // alwaysUse=false — 저장된 요청(SavedRequest)이 있으면 그리로 돌린다.
                                        // true 면 목적지를 버려서, 미로그인 상태로 /memories/42 공유 링크를 열면
                                        // 로그인 후 항상 / 로 가고 원래 화면을 매번 잃는다. 없을 때만 / 다.
                                        .defaultSuccessUrl("/", false)
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
                // 허용목록 재검사는 세션에서 인증이 복원된 뒤, 인가 판정 전에 끼어들어야 한다.
                .addFilterBefore(allowedEmailsRecheck, AuthorizationFilter.class)
                // 로그아웃은 AuthController 가 공통 응답 형식으로 처리한다(프론트가 /api 만 프록시한다).
                .logout(logout -> logout.disable());

        return http.build();
    }
}
