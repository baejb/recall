package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.recall.common.MemoryType;
import com.recall.llm.LlmClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KnowledgeExtraction의 결정론 부분(LLM 응답 파싱·매핑·fallback)을 검증한다. 확률적인 LLM 호출은 fake 포트로 대체해 캔드 응답을 주입한다.
 */
class KnowledgeExtractionTest {

    /** 주어진 문자열을 그대로 반환하는 fake LLM 포트로 추출기를 만든다. */
    private KnowledgeExtraction withLlmResponse(String response) {
        LlmClient fake = (system, user) -> response;
        return new KnowledgeExtraction(fake);
    }

    @Test
    @DisplayName("supports()는 KNOWLEDGE를 반환한다")
    void supports() {
        assertEquals(MemoryType.KNOWLEDGE, withLlmResponse("{}").supports());
    }

    @Test
    @DisplayName("정상 JSON 응답을 카드 필드로 매핑한다")
    void mapsValidJson() {
        String json =
                """
                {"title":"JWT 설정","summary":"만료 1시간","keywords":["jwt","auth"],
                 "facts":["만료 3600초"],"document":"본문"}
                """;
        Map<String, Object> out = withLlmResponse(json).extract("원문");

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
        Map<String, Object> out = withLlmResponse(wrapped).extract("원문");

        assertEquals("t", out.get("title"));
        assertEquals("s", out.get("summary"));
    }

    @Test
    @DisplayName("stub/깨진 응답이면 예외 없이 fallback 카드를 내고 원문을 보존한다")
    void fallsBackOnUnparseableResponse() {
        String input = "JWT 만료를 1시간으로 설정";
        Map<String, Object> out = withLlmResponse("[stub-llm-response]").extract(input);

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
        Map<String, Object> out = withLlmResponse(json).extract("원문");

        assertEquals(List.of(), out.get("keywords"));
        assertEquals(List.of(), out.get("facts"));
    }

    @Test
    @DisplayName("응답의 title이 비면 원문에서 제목을 파생한다")
    void derivesTitleWhenBlank() {
        String json = "{\"title\":\"\",\"summary\":\"s\"}";
        Map<String, Object> out = withLlmResponse(json).extract("원문제목후보");

        assertEquals("원문제목후보", out.get("title"));
    }
}
