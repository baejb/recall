package com.recall.auth.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.auth.AppUserPrincipal;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;

/**
 * 🔴 허용목록에서 빠진 계정의 <b>살아 있는 세션</b>이 계속 통하지 않는지 고정한다.
 *
 * <p>허용목록 판정이 로그인 시점 한 번뿐이면 접근을 끊는 방법이 재시작밖에 없다. 지금은 세션이 인메모리라 재시작이 그 문제를 가리지만, JDBC 세션으로 옮기면 완화가
 * 사라지고 회수 경로가 없어진다 — 그래서 영속화보다 먼저 넣는다.
 */
@Tag("release-gate")
class AllowedEmailsRecheckFilterTest {

    private static final String SUBJECT = "sub-123";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static AppUserPrincipal principal(String email) {
        OidcIdToken idToken =
                OidcIdToken.withTokenValue("token")
                        .claim(StandardClaimNames.SUB, SUBJECT)
                        .claim(StandardClaimNames.EMAIL, email)
                        .build();
        return new AppUserPrincipal(
                AuthorityUtils.NO_AUTHORITIES, idToken, null, StandardClaimNames.SUB, 2L);
    }

    private static void authenticate(AppUserPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    @DisplayName("🔴 허용목록에서 빠진 이메일의 세션은 무효화되고 인증이 지워진다")
    void revokesSessionWhenEmailNoLongerAllowed() throws Exception {
        authenticate(principal("removed@example.com"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        FilterChain chain = new MockFilterChain();

        new AllowedEmailsRecheckFilter(new AuthProperties(List.of("owner@example.com")))
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(session.isInvalid(), "세션이 무효화되지 않았다 — 그 브라우저는 계속 통한다");
        assertNull(
                SecurityContextHolder.getContext().getAuthentication(),
                "인증이 남아 있으면 뒤따르는 인가 판정이 통과시킨다");
    }

    @Test
    @DisplayName("허용목록에 있는 이메일은 그대로 통과한다 — 매 요청 로그아웃되지 않는다")
    void keepsSessionWhenStillAllowed() throws Exception {
        authenticate(principal("owner@example.com"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        new AllowedEmailsRecheckFilter(new AuthProperties(List.of("owner@example.com")))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertTrue(!session.isInvalid(), "허용된 계정의 세션을 무효화했다");
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("우리 principal 이 아니면 건드리지 않는다(익명·다른 인증 방식)")
    void ignoresOtherPrincipalTypes() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                Map.of("sub", SUBJECT), null, AuthorityUtils.NO_AUTHORITIES));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        new AllowedEmailsRecheckFilter(new AuthProperties(List.of("owner@example.com")))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertTrue(!session.isInvalid());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
