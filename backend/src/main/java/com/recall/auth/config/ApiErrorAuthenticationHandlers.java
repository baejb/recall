package com.recall.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.exception.ErrorCode;
import com.recall.common.web.ApiError;
import com.recall.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 인증·인가 실패를 <b>공통 응답 형식</b>으로 답한다(필터 단계라 전역 예외 핸들러가 닿지 못하는 자리).
 *
 * <p><b>왜 리다이렉트가 아닌가</b> — Spring Security 의 기본 동작은 로그인 페이지로 302 다. SPA 의 `fetch` 는 그 리다이렉트를 따라가
 * 로그인 HTML 을 <b>200</b> 으로 받고, 호출부는 "성공했는데 JSON 이 아니다"라는 정체불명의 파싱 실패를 본다. 그래서 API 경로는 상태 코드로 답한다 —
 * 화면이 401 을 보고 로그인으로 보내는 판단을 스스로 하게.
 *
 * <p>HTML 경로(브라우저가 직접 여는 주소)는 다르게 다뤄야 하므로 <b>API 경로에만</b> 이 형식으로 답한다. 401 은 스프링이 경로별 진입점을 받아 주므로
 * ({@code defaultAuthenticationEntryPointFor}) 설정에서 스코프가 잡히지만, <b>403 은 그렇지 않다</b> — {@code
 * accessDeniedHandler} 는 경로를 받지 않아 전역이다. 그래서 여기서 매처를 받아 직접 가른다. 이 스코프가 없으면 브라우저로 직접 여는 경로의 403
 * (CSRF 실패가 대표)에서 화면 대신 {@code {"success":false,…}} JSON 이 그대로 노출된다.
 */
public final class ApiErrorAuthenticationHandlers {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorAuthenticationHandlers.class);

    // 이 앱은 주입 가능한 ObjectMapper 빈이 없다 — 코드베이스 관례대로 내부에서 만든다(CardCodec·StorePipeline 등과 동일).
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiErrorAuthenticationHandlers() {}

    /** 401 — 세션이 없거나 만료됐다. */
    public static AuthenticationEntryPoint entryPoint() {
        return (request, response, exception) ->
                write(request, response, ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다", exception);
    }

    /**
     * 403 — 인증은 됐지만 이 요청이 허용되지 않는다(대표 사례: CSRF 토큰 불일치).
     *
     * @param apiPaths JSON 으로 답할 경로. 그 밖(브라우저가 직접 여는 HTML 경로)은 스프링 기본 403 처리에 맡긴다
     */
    public static AccessDeniedHandler accessDeniedHandler(RequestMatcher apiPaths) {
        AccessDeniedHandler htmlDefault = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (!apiPaths.matches(request)) {
                htmlDefault.handle(request, response, exception);
                return;
            }
            write(request, response, ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다", exception);
        };
    }

    private static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode code,
            String message,
            RuntimeException cause)
            throws IOException {
        if (response.isCommitted()) {
            // 응답이 이미 시작된 뒤(SSE 스트리밍 중)엔 아무것도 쓸 수 없다 — 쓰려는 시도 자체가 다시 실패해
            // 원래 원인을 로그에서 가린다(GlobalExceptionHandler 와 같은 규칙).
            log.warn("응답이 이미 커밋돼 인증 실패 응답을 쓰지 않는다 — {}", code);
            return;
        }
        ApiError error = ApiError.of(code, message, null);
        log.warn(
                "인증 실패 응답 {} {} path={} traceId={} ({})",
                code.status().value(),
                error.code(),
                request.getRequestURI(),
                error.traceId(),
                describe(cause));

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), ApiResponse.fail(error));
    }

    /** 원인 요약(응답에는 싣지 않는다 — 내부 사정을 노출하지 않기 위해). */
    private static String describe(RuntimeException cause) {
        if (cause instanceof AuthenticationException || cause instanceof AccessDeniedException) {
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        return String.valueOf(cause);
    }
}
