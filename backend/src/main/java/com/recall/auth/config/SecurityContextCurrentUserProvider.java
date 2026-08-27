package com.recall.auth.config;

import com.recall.auth.AppUserPrincipal;
import com.recall.common.config.CurrentUserProvider;
import com.recall.common.exception.UnauthenticatedException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * OAuth 모드의 {@link CurrentUserProvider} — SecurityContext 의 {@link AppUserPrincipal} 에서 {@code
 * app_user.id} 를 읽는다. {@code BootstrapCurrentUserProvider}(항상 id=1)를 대체한다.
 *
 * <p>전환은 <b>Spring 프로필 {@code oauth}</b> 하나로 결정된다 — 이 클래스는 그 프로필에서만, 부트스트랩 provider 는 그 프로필이 아닐 때만
 * 등록된다. 두 스위치(프로필 + 속성)를 두면 하나만 켜진 상태가 만들어지고, 그때 <b>로그인은 되는데 모든 데이터가 id=1 로 스코프되는</b> 가장 위험한 오설정이
 * 조용히 성립한다.
 *
 * <p>principal 이 없거나 예상 타입이 아니면 <b>던진다</b>(401). 이 자리에서 기본값(예: 1)으로 넘어가면 인증 없이 남의 데이터에 닿는 경로가 열린다 —
 * 소유자 해석은 실패해야 할 때 반드시 실패해야 한다(🔴 교차유출).
 */
@Configuration
@Profile("oauth")
public class SecurityContextCurrentUserProvider {

    @Bean
    CurrentUserProvider currentUserProvider() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null
                    || !authentication.isAuthenticated()
                    || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
                throw new UnauthenticatedException("로그인이 필요합니다");
            }
            return principal.appUserId();
        };
    }
}
