package com.recall.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * "응답이 이미 커밋됐으면 아무것도 쓰지 않는다" 가 <b>모든 핸들러</b>에 적용되는지.
 *
 * <p>이 규칙은 조건 없는 규칙인데 한동안 async 타임아웃 핸들러에만 손으로 들어가 있었다. 그래서 SSE 스트리밍 중 터진 <b>비-타임아웃</b> 예외(근거 전송 실패
 * → {@code completeWithError})가 catch-all 로 가서, 이미 커밋된 응답에 500 본문을 쓰려 했다 — 그 시도 자체가 다시 실패해 로그에 원인이
 * 가려진다. 지금은 응답을 만드는 공통 통로가 검사하므로 핸들러 종류와 무관하다.
 */
@Tag("unit")
class CommittedResponseGuardTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 요청 컨텍스트를 바인딩한다. {@code committed} 면 응답의 첫 바이트가 이미 나간 상태를 만든다. */
    private static void bindResponse(boolean committed) throws IOException {
        HttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (committed) {
            response.getWriter().write("data: 첫 조각\n\n");
            response.flushBuffer(); // 여기서 isCommitted() 가 true 가 된다
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    }

    @Test
    @DisplayName("🟠 커밋된 응답에서는 catch-all 도 응답을 쓰지 않는다")
    void catchAllWritesNothingAfterCommit() throws IOException {
        bindResponse(true);

        assertNull(handler.handleUnexpected(new IllegalStateException("스트리밍 중 실패")));
    }

    @Test
    @DisplayName("커밋된 응답에서는 async 타임아웃도 응답을 쓰지 않는다")
    void asyncTimeoutWritesNothingAfterCommit() throws IOException {
        bindResponse(true);

        assertNull(handler.handleAsyncTimeout(new AsyncRequestTimeoutException()));
    }

    @Test
    @DisplayName("아직 아무것도 안 보냈으면 평소대로 에러 응답을 쓴다 — 가드가 정상 경로를 막지 않는다")
    void writesEnvelopeWhenNothingSentYet() throws IOException {
        bindResponse(false);

        var response = handler.handleAsyncTimeout(new AsyncRequestTimeoutException());

        assertNotNull(response);
        assertEquals(503, response.getStatusCode().value());
    }

    @Test
    @DisplayName("요청 컨텍스트가 없으면 커밋 여부를 알 수 없으므로 정상 경로를 탄다")
    void writesEnvelopeWithoutRequestContext() {
        RequestContextHolder.resetRequestAttributes();

        assertNotNull(handler.handleUnexpected(new IllegalStateException("컨텍스트 밖")));
    }
}
