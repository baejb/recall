package com.recall.auth.config;

import com.recall.auth.AppUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 살아 있는 세션의 이메일이 <b>아직도</b> 허용목록에 있는지 요청마다 다시 본다.
 *
 * <p><b>왜 필요한가</b> — 허용목록 판정은 원래 로그인 시점 한 번뿐이었다. 그래서 이런 경로가 있었다:
 *
 * <ol>
 *   <li>{@code owner@ex.com} 로그인 → 세션 + {@link AppUserPrincipal}
 *   <li>{@code RECALL_ALLOWED_EMAILS} 에서 그 이메일을 빼고 재배포
 *   <li>그 브라우저는 여전히 전부 200 — 기억 조회·쓰기 모두 가능
 * </ol>
 *
 * <p>즉 <b>접근을 끊는 방법이 재시작뿐</b>이었다. 지금은 세션이 인메모리라 재시작이 이 문제를 가리고 있지만, JDBC 세션으로 옮기면 그 완화가 사라지고 회수 경로가
 * 아예 없어진다. 세션 영속화와 요청 시점 재검사는 같이 가야 한다 — 그래서 영속화보다 먼저 넣는다.
 *
 * <p>비용은 메모리 설정 비교 하나다 — 허용 이메일은 몇 개짜리 목록이고 DB 를 타지 않는다.
 *
 * <p>{@code @Profile("oauth")} 인 이유는 두 가지다. (1) 부트스트랩 모드에는 세션도 허용목록도 없어 할 일이 없다. (2) 프로필이 없으면
 * {@code @WebMvcTest} 슬라이스가 이 빈을 집는다 — 그 슬라이스는 {@code Filter} 타입은 포함하지만
 * {@code @Configuration}({@code AuthConfig})은 제외하므로 {@link AuthProperties} 주입이 실패하고, 인증과 무관한 컨트롤러
 * 테스트가 통째로 깨진다.
 *
 * <p><b>직접 응답을 쓰지 않는다</b> — 세션을 무효화하고 컨텍스트를 비운 뒤 체인을 계속 흘린다. 그러면 뒤따르는 인가 판정이 미인증으로 보고 <b>이미 있는</b>
 * 실패 경로를 탄다(API 경로는 401 JSON, HTML 경로는 스프링 기본). 여기서 응답을 직접 만들면 그 분기를 두 곳에서 관리하게 된다.
 */
@Component
@Profile("oauth")
class AllowedEmailsRecheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AllowedEmailsRecheckFilter.class);

    private final AuthProperties authProperties;

    AllowedEmailsRecheckFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AppUserPrincipal principal
                && !authProperties.allows(principal.email())) {
            log.warn(
                    "허용목록에서 빠진 계정의 살아 있는 세션 — 무효화한다. appUserId={} email={}",
                    principal.appUserId(),
                    principal.email());
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
