package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KnowledgeExtraction의 결정론 부분(LLM 응답 파싱·매핑·fallback)을 검증한다. 확률적인 LLM 호출은 fake 포트로 대체해 캔드 응답을 주입한다.
 * LLM은 이제 생성자 주입 싱글턴이 아니라 매 호출 {@code ctx.requireChat()}로 얻으므로(사용자별 provider/키 교차유출 방지), 모든 호출은
 * ctx를 통해 클라이언트를 넘긴다.
 */
class KnowledgeExtractionTest {

    private static final KnowledgeExtraction EXTRACTOR =
            new KnowledgeExtraction(new PromptLoader());

    /** 주어진 문자열을 그대로 반환하는 fake LLM 포트를 ctx에 바인딩한다(프롬프트는 실제 리소스 로드). */
    private static UserAiContext ctxWithResponse(String response) {
        LlmClient fake = (system, user) -> response;
        return new UserAiContext(1L, fake, mock(EmbeddingClient.class), true, true);
    }

    @Test
    @DisplayName("supports()는 KNOWLEDGE를 반환한다")
    void supports() {
        assertEquals(MemoryType.KNOWLEDGE, EXTRACTOR.supports());
    }

    @Test
    @DisplayName("정상 JSON 응답을 카드 필드로 매핑한다")
    void mapsValidJson() {
        String json =
                """
                {"title":"JWT 설정","summary":"만료 1시간","keywords":["jwt","auth"],
                 "facts":["만료 3600초"],"document":"본문"}
                """;
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(json));

        assertEquals("JWT 설정", out.get("title"));
        assertEquals("만료 1시간", out.get("summary"));
        assertEquals(List.of("jwt", "auth"), out.get("keywords"));
        assertEquals(List.of("만료 3600초"), out.get("facts"));
        assertEquals("본문", out.get("document"));
    }

    @Test
    @DisplayName("산문/마크다운에 감싸인 JSON도 본문만 추출해 파싱한다")
    void extractsJsonWrappedInProse() {
        String wrapped = "다음은 결과입니다:\n```json\n{\"title\":\"t\",\"summary\":\"s\"}\n```\n이상.";
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(wrapped));

        assertEquals("t", out.get("title"));
        assertEquals("s", out.get("summary"));
    }

    @Test
    @DisplayName("stub/깨진 응답이면 예외 없이 fallback 카드를 내고 원문을 보존한다")
    void fallsBackOnUnparseableResponse() {
        String input = "JWT 만료를 1시간으로 설정";
        Map<String, Object> out = EXTRACTOR.extract(input, ctxWithResponse("[stub-llm-response]"));

        // 승인 시 memory.title이 비지 않도록 title 보장
        assertNotNull(out.get("title"));
        assertFalse(((String) out.get("title")).isBlank());
        // 원문 유실 금지
        assertEquals(input, out.get("summary"));
        assertEquals(input, out.get("document"));
        assertEquals(List.of(), out.get("keywords"));
        assertEquals(List.of(), out.get("facts"));
    }

    @Test
    @DisplayName("keywords/facts가 누락되면 빈 리스트로 정규화한다")
    void normalizesMissingLists() {
        String json = "{\"title\":\"t\",\"summary\":\"s\",\"document\":\"d\"}";
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(json));

        assertEquals(List.of(), out.get("keywords"));
        assertEquals(List.of(), out.get("facts"));
    }

    @Test
    @DisplayName("응답의 title이 비면 원문에서 제목을 파생한다")
    void derivesTitleWhenBlank() {
        String json = "{\"title\":\"\",\"summary\":\"s\"}";
        Map<String, Object> out = EXTRACTOR.extract("원문제목후보", ctxWithResponse(json));

        assertEquals("원문제목후보", out.get("title"));
    }

    @Test
    @DisplayName("🔴 ctx에 바인딩된 LlmClient만 호출된다 — 다른(남의) ctx의 클라이언트는 절대 안 건드림")
    void usesOnlyTheGivenCtxLlmClientNotAnyOtherOne() {
        LlmClient ownerClient = mock(LlmClient.class);
        when(ownerClient.complete(any(), any())).thenReturn("{\"title\":\"t\",\"summary\":\"s\"}");
        LlmClient otherUsersClient = mock(LlmClient.class);

        UserAiContext ownerCtx =
                new UserAiContext(1L, ownerClient, mock(EmbeddingClient.class), true, true);

        EXTRACTOR.extract("원문", ownerCtx);

        verify(ownerClient).complete(any(), any());
        verify(otherUsersClient, never()).complete(any(), any());
    }
}
