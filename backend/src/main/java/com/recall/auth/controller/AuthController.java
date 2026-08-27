package com.recall.auth.controller;

import com.recall.auth.AppUserPrincipal;
import com.recall.auth.controller.dto.MeResponse;
import com.recall.common.config.CurrentUserProvider;
import com.recall.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 세션 상태를 화면에 알려주는 창구.
 *
 * <p><b>CSRF 쿠키 발급은 이 컨트롤러의 일이 아니다</b> — 전에는 {@code me} 가 {@code CsrfToken} 을 파라미터로 받아 "토큰을 건드려 쿠키가
 * 나가게" 했다. 그런데 {@code OAuthSecurityConfig} 의 {@code setCsrfRequestAttributeName(null)} 이 이미 지연 로딩을
 * 꺼서 매 요청 쿠키가 나가고 있었다. 같은 목적의 장치가 두 곳에 있고 서로를 몰랐고, 이쪽 파라미터는 읽지도 반환하지도 않는 미사용 인자라 린터·리뷰에서 먼저 지워질
 * 쪽이었다 — 둘 다 지워지면 첫 상태변경 POST 가 403 이 되고 원인이 "로그인 문제"처럼 보인다. 그래서 장치를 설정 쪽 하나로 모았다.
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
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AppUserPrincipal principal) {
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
     * <p>Spring Security 의 기본 {@code /logout} 대신 {@code /api} 아래 두는 이유: 프론트가 {@code fetch} 로 부르는
     * 호출이므로 응답이 <b>공통 응답 형식</b>이어야 한다. 기본 {@code /logout} 은 리다이렉트로 답해서, SPA 의 호출부가 "성공했는데 JSON 이
     * 아니다"라는 정체불명의 파싱 실패를 본다.
     *
     * <p>전에 이 자리에 "프론트가 쓰는 모든 경로가 {@code /api} 로 프록시되므로 프록시 규칙을 하나 더 만들지 않으려고"라고 적혀 있었는데, 그 전제는 사실이
     * 아니었다 — 로그인 시작({@code /oauth2/**})과 콜백({@code /login/**})이 이미 {@code /api} 밖이고, 오히려 그 규칙이 없어서
     * 로그인이 백엔드에 닿지 못했다. 지금은 세 경로를 모두 프록시한다({@code nginx/CLAUDE.md}).
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
