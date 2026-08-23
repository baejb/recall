package com.recall.auth.controller;

import com.recall.auth.AppUserPrincipal;
import com.recall.auth.controller.dto.MeResponse;
import com.recall.common.config.CurrentUserProvider;
import com.recall.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 세션 상태를 화면에 알려주는 창구.
 *
 * <p><b>{@code CsrfToken} 을 파라미터로 받는 이유</b> — CSRF 토큰 쿠키는 토큰을 실제로 <b>읽을 때</b> 발급된다(지연 생성). SPA 는 부팅
 * 직후 {@code /api/me} 를 부르므로 그 자리에서 토큰을 건드려 쿠키가 나가게 한다. 이걸 빼면 첫 상태변경 POST 가 토큰 없이 나가 403 이 되고, 원인이
 * "로그인 문제"처럼 보인다.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final CurrentUserProvider currentUser;

    public AuthController(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * 현재 로그인 사용자. 인증되지 않았으면 이 경로에 닿기 전에 401 로 막힌다(부트스트랩 모드는 예외 — 아래).
     *
     * <p>부트스트랩 모드(인증 미배선)에서는 principal 이 없으므로 소유자 provider 가 주는 id 를 그대로 싣고 {@code
     * bootstrapMode=true} 로 알린다. 화면이 그 사실을 표시할 수 있어야 한다 — "로그인 없이 단일 사용자로 동작 중"을 숨기면 열린 인스턴스가 정상처럼
     * 보인다.
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(
            @AuthenticationPrincipal AppUserPrincipal principal, CsrfToken csrfToken) {
        if (principal == null) {
            return ApiResponse.ok(new MeResponse(currentUser.currentUserId(), "", "", true));
        }
        return ApiResponse.ok(
                new MeResponse(
                        principal.appUserId(), principal.email(), principal.displayName(), false));
    }

    /**
     * 로그아웃 — 세션을 무효화한다.
     *
     * <p>Spring Security 의 기본 {@code /logout} 대신 {@code /api} 아래 두는 이유: 프론트가 쓰는 모든 경로가 {@code /api}
     * 로 프록시되므로(dev 는 vite, 배포는 nginx) 로그아웃만 다른 경로에 두면 프록시 규칙을 하나 더 만들어야 한다. 응답도 공통 형식으로 맞춘다.
     */
    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok();
    }
}
