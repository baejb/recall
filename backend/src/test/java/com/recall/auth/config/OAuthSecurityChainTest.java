package com.recall.auth.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recall.auth.AppUserPrincipal;
import com.recall.common.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code oauth} 프로필의 필터 체인이 실제로 어떻게 답하는지 고정한다. 단위테스트로는 닿지 않는 자리다 — 여기서 검증하는 것들은 모두 <b>필터 체인 배선의
 * 결과</b>이고, 배선을 한 줄 지우면 조용히 사라지는 종류다.
 *
 * <p>더미 client-id 로 체인을 띄운다. Google 왕복은 여기서 검증할 수 없지만(실 자격증명 필요), <b>요청이 백엔드 체인 안에서 어떻게 다뤄지는지</b>는
 * 그것과 무관하게 고정할 수 있다.
 */
@SpringBootTest(
        properties = {
            "GOOGLE_OAUTH_CLIENT_ID=test-client-id",
            "GOOGLE_OAUTH_CLIENT_SECRET=test-client-secret",
            "RECALL_ALLOWED_EMAILS=owner@example.com"
        })
@AutoConfigureMockMvc
@ActiveProfiles("oauth")
@Tag("release-gate")
class OAuthSecurityChainTest {

    @Autowired private MockMvc mockMvc;

    /**
     * 🔴 CSRF 쿠키가 <b>어느 GET 에서든</b> 나가는지 고정한다.
     *
     * <p>이걸 지키는 것은 {@code OAuthSecurityConfig} 의 {@code setCsrfRequestAttributeName(null)} 한 줄이다(지연
     * 로딩을 꺼서 {@code CsrfFilter} 가 매 요청 토큰을 로드·저장한다). 전에는 {@code AuthController.me} 가 {@code
     * CsrfToken} 을 파라미터로 받아 같은 일을 하려 했고, 같은 목적의 장치가 두 곳에 있으면서 서로를 몰랐다 — 둘 다 지워지면 첫 상태변경 POST 가 403
     * 이 되고 원인이 "로그인 문제"처럼 보인다. 이제 장치는 한 곳이고, 그 줄을 지우면 이 테스트가 깨진다.
     */
    @Test
    @DisplayName("🔴 CSRF 쿠키(XSRF-TOKEN)는 특정 호출이 아니라 어느 요청에서든 발급된다")
    void csrfCookieIsIssuedOnAnyRequest() throws Exception {
        // /api/me 가 아닌 공개 경로로 부른다 — 발급이 특정 엔드포인트에 매여 있지 않음을 보이기 위해.
        var cookie =
                mockMvc.perform(get("/api/health"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");

        assertNotNull(cookie, "XSRF-TOKEN 쿠키가 발급되지 않았다 — 첫 상태변경 POST 가 403 이 된다");
    }

    @Test
    @DisplayName("🔴 인증 없는 API 요청은 리다이렉트가 아니라 401 JSON — SPA 의 fetch 가 로그인 HTML 을 200 으로 받지 않는다")
    void unauthenticatedApiRequestGetsJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/memories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    /**
     * CSRF 토큰 없는 상태변경 요청은 403 이고, API 경로이므로 JSON 이다.
     *
     * <p>이 앱에서 {@code FORBIDDEN} 이 실제로 나가는 경로가 여기다 — 허용목록 밖 계정은 403 이 아니라 {@code
     * /?login_error=not_allowed} 로 302 다. 그 계약이 코드에 없는데 문서 세 곳에 적혀 있었다.
     */
    @Test
    @DisplayName("CSRF 토큰 없는 API POST 는 403 JSON — 이게 FORBIDDEN 이 실제로 나가는 경로다")
    void apiPostWithoutCsrfTokenGetsJsonForbidden() throws Exception {
        mockMvc.perform(
                        post("/api/captures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rawText\":\"본문\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.FORBIDDEN.name()));
    }

    /**
     * 🔴 401 로 끝난 API 요청을 <b>로그인 후 목적지로 저장하지 않는다</b>.
     *
     * <p>SPA 는 부팅 직후 세션이 없는 상태로 {@code /api/me}·{@code /api/reviews} 를 부른다. 그 요청들은 401 이고, 스프링의 기본
     * {@code RequestCache} 는 그것을 "사용자가 가려던 곳"으로 보고 세션에 저장한다({@code fetch} 가 {@code Accept} 를 붙이지 않아
     * 와일드카드로 나가므로 기본 제외 규칙 — JSON·XHR — 에 걸리지 않는다). 그러면 로그인 성공 후 {@code defaultSuccessUrl("/",
     * false)} 가 그 저장된 요청을 우선해서, 사용자는 화면 대신 <b>API 응답</b>에 떨어진다.
     *
     * <p>dev 에서는 증상이 더 나쁘다 — vite 가 {@code /api} 를 {@code changeOrigin: true} 로 프록시해 Host 가 {@code
     * :8080} 으로 바뀌므로, 저장된 절대 URL 이 백엔드 오리진이 된다. 로그인하면 SPA 가 없는 {@code :8080} 으로 튕겨 404 를 본다.
     *
     * <p>그래서 API 경로는 애초에 저장 대상에서 뺀다. 진짜 화면 이동(공유 링크 {@code /memories/42})은 그대로 저장돼 {@code
     * alwaysUse=false} 의 의도가 살아 있다.
     */
    @Test
    @DisplayName("🔴 401 로 끝난 API 요청은 로그인 후 목적지로 저장되지 않는다 — 로그인하면 화면이 아니라 API 응답에 떨어진다")
    void unauthenticatedApiRequestIsNotSavedAsPostLoginDestination() throws Exception {
        // SPA 의 fetch 를 그대로 흉내낸다 — Accept 를 붙이지 않으므로 */* 로 나간다.
        HttpSession session =
                mockMvc.perform(get("/api/me"))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        // 세션이 아예 안 생겼다면 저장된 목적지도 없다(그게 이상적이다). 생겼다면 그 안이 비어 있어야 한다.
        SavedRequest saved = null;
        if (session != null) {
            MockHttpServletRequest probe = new MockHttpServletRequest();
            probe.setSession((MockHttpSession) session);
            saved = new HttpSessionRequestCache().getRequest(probe, new MockHttpServletResponse());
        }

        assertNull(saved, "401 로 끝난 API 요청이 로그인 후 목적지로 저장됐다 — 로그인하면 화면 대신 그 API 응답으로 간다");
    }

    /**
     * 로그인한 세션을 흉내낸다 — 실제 Google 왕복 없이 체인 뒤쪽(인가·CSRF·컨트롤러)을 검증하기 위해.
     *
     * <p>이메일은 이 클래스가 띄운 컨텍스트의 허용목록(`owner@example.com`)과 같아야 한다. 다르면 {@code
     * AllowedEmailsRecheckFilter} 가 먼저 세션을 끊어, 로그아웃이 아니라 재검사가 통과시킨 결과를 보게 된다.
     */
    private static MockHttpSession authenticatedSession() {
        OidcIdToken idToken =
                OidcIdToken.withTokenValue("token")
                        .claim(StandardClaimNames.SUB, "sub-logout")
                        .claim(StandardClaimNames.EMAIL, "owner@example.com")
                        .build();
        AppUserPrincipal principal =
                new AppUserPrincipal(
                        AuthorityUtils.createAuthorityList("ROLE_USER"),
                        idToken,
                        null,
                        StandardClaimNames.SUB,
                        2L);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    /**
     * 🔴 로그아웃이 <b>세션을 실제로 무효화</b>하는지 고정한다.
     *
     * <p>이 경로에는 테스트가 없었다. {@code AuthController.logout} 은 {@code session.invalidate()} 한 줄이고,
     * Spring Security 의 기본 로그아웃은 {@code logout.disable()} 로 꺼져 있다 — 즉 이 한 줄이 지워지거나 {@code
     * getSession(false)} 가 {@code null} 을 받는 형태로 바뀌면, <b>화면은 로그아웃된 것처럼 보이는데 세션은 살아 있다</b>. 프론트가 응답과
     * 무관하게 익명으로 되돌리기 때문에({@code useSession} 의 {@code finally}) 증상이 드러나지 않는다.
     */
    @Test
    @DisplayName("🔴 로그아웃은 세션을 무효화한다 — 화면만 익명이 되고 세션이 남으면 그 쿠키는 계속 통한다")
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = authenticatedSession();

        // CSRF 토큰을 먼저 받는다 — 상태변경 POST 라 없으면 403 이다(로그아웃이 막히는 실제 경로).
        Cookie csrf =
                mockMvc.perform(get("/api/health").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertNotNull(csrf, "XSRF-TOKEN 쿠키가 없다 — 로그아웃 POST 가 403 이 된다");
        assertFalse(session.isInvalid(), "로그아웃 전에 세션이 이미 죽어 있다 — 이 테스트의 전제가 깨졌다");

        mockMvc.perform(
                        post("/api/auth/logout")
                                .session(session)
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertTrue(session.isInvalid(), "로그아웃했는데 세션이 살아 있다 — 그 쿠키를 가진 브라우저는 계속 통한다");
    }

    @Test
    @DisplayName("로그인 시작 경로는 열려 있고 provider 로 리다이렉트한다(프록시가 이 경로를 백엔드로 보내야 한다)")
    void loginStartRedirectsToProvider() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(
                        header().string(
                                        "Location",
                                        org.hamcrest.Matchers.containsString("google")));
    }
}
