package com.recall.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 프레임워크 예외 → 에러 응답 매핑 회귀.
 *
 * <p>전역 핸들러가 {@code ResponseEntityExceptionHandler} 상속을 떼면서 <b>스프링 MVC 표준 예외의 커버리지가 함께 사라졌다</b>.
 * 그러면 호출자가 헤더·메서드·경로만 고치면 되는 4xx 상황이 맨 아래 {@code Exception} 핸들러에 걸려 <b>500</b>으로 나가고, "서버가 고장났다"는
 * 잘못된 신호가 로그와 화면에 남는다. 각 상황이 제 상태로 나가는지, 그리고 <b>응답의 code 가 그 상태와 짝이 맞는지</b>를 고정한다 (코드가 상태를 소유한다는
 * {@link ErrorCode} 계약 — 실제로 405 가 400 짝인 코드를 싣고 나간 적이 있다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerWebTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("JSON 엔드포인트에 text/plain 을 보내면 415 — catch-all 500 이 아니다")
    void unsupportedMediaTypeIsNotServerError() throws Exception {
        mockMvc.perform(post("/api/captures").contentType(MediaType.TEXT_PLAIN).content("그냥 텍스트"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name()));
    }

    @Test
    @DisplayName("라우트에 없는 메서드는 405이고, 응답의 code 도 405 짝이다(400 짝을 싣지 않는다)")
    void methodNotAllowedCarriesMatchingCode() throws Exception {
        mockMvc.perform(delete("/api/captures"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.METHOD_NOT_ALLOWED.name()));
    }

    @Test
    @DisplayName("경로 변수 타입 불일치는 400 — 오타 난 URL 이 500 으로 나가지 않는다")
    void pathVariableTypeMismatchIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/memories/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_ERROR.name()))
                .andExpect(jsonPath("$.error.field").value("id"));
    }

    @Test
    @DisplayName("모든 에러 응답에는 traceId 가 있다 — 사용자가 보고한 값으로 로그를 찾을 수 있어야 한다")
    void errorEnvelopeAlwaysCarriesTraceId() throws Exception {
        mockMvc.perform(get("/api/memories/{id}", "abc"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("🔴 에러 응답은 Accept 와 협상하지 않는다 — SSE 소비자에게도 본문이 도착한다")
    void errorEnvelopeIgnoresAcceptNegotiation() throws Exception {
        // 응답 객체를 그냥 반환하면 Accept 협상 실패가 핸들러 안에서 터지고, 이미 예외 처리 중이라 다시 잡히지
        // 않아 원 예외가 재전파돼 **500 + 빈 본문**이 나갔다. SSE 소비자(text/event-stream)에게 409·400 이
        // 모두 빈 본문으로 갔다(라이브 재현). 실패 사실은 요청이 무엇을 받겠다고 했든 전달돼야 한다.
        mockMvc.perform(
                        post("/api/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_ERROR.name()))
                .andExpect(jsonPath("$.error.field").value("question"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }
}
