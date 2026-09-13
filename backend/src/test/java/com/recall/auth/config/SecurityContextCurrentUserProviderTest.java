package com.recall.auth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.recall.auth.AppUserPrincipal;
import com.recall.common.config.CurrentUserProvider;
import com.recall.common.exception.UnauthenticatedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 🔴 소유자 해석은 <b>실패해야 할 때 실패해야 한다</b>.
 *
 * <p>이 provider 가 principal 을 못 찾았을 때 기본값(예: 1)으로 넘어가면 인증 없는 요청이 부트스트랩 사용자의 데이터에 닿는다. 그건 로그인을 켠 의미를
 * 없애는 경로이고, 화면상으로는 정상으로 보인다(🔴 교차유출). 그래서 던지는 동작을 회귀로 고정한다.
 */
@Tag("release-gate")
class SecurityContextCurrentUserProviderTest {

    private final CurrentUserProvider provider =
            new SecurityContextCurrentUserProvider().currentUserProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("principal 의 app_user.id 를 그대로 소유자로 쓴다")
    void resolvesAppUserIdFromPrincipal() {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.appUserId()).thenReturn(42L);
        authenticate(principal);

        assertEquals(42L, provider.currentUserId());
    }

    @Test
    @DisplayName("🔴 SecurityContext 가 비면 401 — 기본 사용자로 넘어가지 않는다")
    void throwsWhenNoAuthentication() {
        SecurityContextHolder.clearContext();

        assertThrows(UnauthenticatedException.class, provider::currentUserId);
    }

    @Test
    @DisplayName("🔴 인증되지 않은 토큰이면 401")
    void throwsWhenNotAuthenticated() {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        TestingAuthenticationToken token = new TestingAuthenticationToken(principal, null);
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThrows(UnauthenticatedException.class, provider::currentUserId);
    }

    @Test
    @DisplayName("🔴 principal 이 예상 타입이 아니면 401 — 익명·다른 인증 방식이 소유자로 해석되지 않는다")
    void throwsWhenPrincipalIsNotAppUser() {
        authenticate("anonymousUser");

        assertThrows(UnauthenticatedException.class, provider::currentUserId);
    }

    private static void authenticate(Object principal) {
        Authentication token = new TestingAuthenticationToken(principal, null, "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
