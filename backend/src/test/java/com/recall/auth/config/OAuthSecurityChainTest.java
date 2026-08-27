package com.recall.auth.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recall.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
