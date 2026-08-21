package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * TroubleshootingExtraction의 결정론 부분(LLM 응답 파싱·매핑·정규화·fallback)을 검증한다. 확률적인 LLM 호출은 fake 포트로 대체해 캔드
 * 응답을 주입한다(KnowledgeExtractionTest와 같은 구성).
 */
class TroubleshootingExtractionTest {

    private static final TroubleshootingExtraction EXTRACTOR =
            new TroubleshootingExtraction(new PromptLoader());

    /** 주어진 문자열을 그대로 반환하는 fake LLM 포트를 ctx에 바인딩한다(프롬프트는 실제 리소스 로드). */
    private static UserAiContext ctxWithResponse(String response) {
        LlmClient fake = (system, user) -> response;
        return new UserAiContext(1L, fake, mock(EmbeddingClient.class), true, true);
    }

    private static final String FULL_JSON =
            """
            {"title":"컨테이너 OOM","summary":"메모리 한도 초과로 죽었다",
             "keywords":["docker","oom"],
             "symptom":"컨테이너가 5분마다 죽는다",
             "error_message":"Killed process 1 (java) total-vm",
             "error_signature":"OOMKilled exit 137",
             "environment":"Docker 27 / 2GB limit",
             "attempts":[
               {"action":"docker restart","result":"5분 뒤 재발","outcome":"failed"},
               {"action":"heap dump 분석","result":"누수 없음","outcome":"failed"},
               {"action":"메모리 한도 4GB 상향","result":"에러 사라짐","outcome":"worked"}],
             "root_cause":"JVM heap이 컨테이너 한도를 넘김",
             "final_solution":"limit 4GB + MaxRAMPercentage=75",
             "status":"RESOLVED"}
            """;

    @Test
    @DisplayName("supports()는 TROUBLESHOOTING을 반환한다")
    void supports() {
        assertEquals(MemoryType.TROUBLESHOOTING, EXTRACTOR.supports());
    }

    @Test
    @DisplayName("정상 JSON 응답을 PRD 스키마(snake_case) 필드로 매핑한다")
    void mapsValidJson() {
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(FULL_JSON));

        assertEquals("컨테이너 OOM", out.get("title"));
        assertEquals("메모리 한도 초과로 죽었다", out.get("summary"));
        assertEquals("컨테이너가 5분마다 죽는다", out.get("symptom"));
        assertEquals("OOMKilled exit 137", out.get("error_signature"));
        assertEquals("Docker 27 / 2GB limit", out.get("environment"));
        assertEquals("JVM heap이 컨테이너 한도를 넘김", out.get("root_cause"));
        assertEquals("limit 4GB + MaxRAMPercentage=75", out.get("final_solution"));
        assertEquals("RESOLVED", out.get("status"));
    }

    @Test
    @DisplayName("🟠 실패한 시도를 버리지 않는다 — attempts 3건이 action·result·outcome까지 보존된다")
    void preservesAllAttemptsIncludingFailures() {
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(FULL_JSON));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) out.get("attempts");
        assertEquals(3, attempts.size(), "실패 시도까지 모두 남아야 한다");
        assertEquals("docker restart", attempts.get(0).get("action"));
        assertEquals("5분 뒤 재발", attempts.get(0).get("result"));
        assertEquals("failed", attempts.get(0).get("outcome"));
        assertEquals("worked", attempts.get(2).get("outcome"));
        assertEquals(
                2,
                attempts.stream().filter(a -> "failed".equals(a.get("outcome"))).count(),
                "outcome=failed 2건이 유실되지 않아야 한다");
    }

    @Test
    @DisplayName("error_signature가 keywords에 없으면 채워 넣는다(정확 토큰 키워드 검색 대상)")
    void addsErrorSignatureToKeywords() {
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(FULL_JSON));

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) out.get("keywords");
        assertTrue(keywords.contains("OOMKilled exit 137"), "시그니처가 keywords에 들어가야 BM25로 찾힌다");
        assertTrue(keywords.contains("docker"), "모델이 준 키워드는 유지");
    }

    @Test
    @DisplayName("status는 허용 값으로 정규화하고, 모르는 값은 UNRESOLVED로 둔다(해결됐다고 단정하지 않음)")
    void normalizesStatus() {
        assertEquals("RESOLVED", statusOf("{\"title\":\"t\",\"status\":\"resolved\"}"));
        assertEquals("PARTIAL", statusOf("{\"title\":\"t\",\"status\":\" partial \"}"));
        assertEquals("UNRESOLVED", statusOf("{\"title\":\"t\",\"status\":\"해결중\"}"));
        assertEquals("UNRESOLVED", statusOf("{\"title\":\"t\"}"));
    }

    private static Object statusOf(String llmResponse) {
        return EXTRACTOR.extract("원문", ctxWithResponse(llmResponse)).get("status");
    }

    @Test
    @DisplayName("outcome이 없거나 모르는 값이면 unknown으로 둔다(failed로 위장하지 않음)")
    void normalizesOutcome() {
        String json =
                "{\"title\":\"t\",\"attempts\":[{\"action\":\"a\",\"result\":\"r\"},"
                        + "{\"action\":\"b\",\"result\":\"r\",\"outcome\":\"성공\"}]}";
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(json));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) out.get("attempts");
        assertEquals("unknown", attempts.get(0).get("outcome"));
        assertEquals("unknown", attempts.get(1).get("outcome"));
    }

    @Test
    @DisplayName("산문/마크다운에 감싸인 JSON도 본문만 추출해 파싱한다")
    void extractsJsonWrappedInProse() {
        String wrapped = "결과입니다:\n```json\n{\"title\":\"t\",\"symptom\":\"s\"}\n```\n이상.";
        Map<String, Object> out = EXTRACTOR.extract("원문", ctxWithResponse(wrapped));

        assertEquals("t", out.get("title"));
        assertEquals("s", out.get("symptom"));
    }

    @Test
    @DisplayName("stub/깨진 응답이면 예외 없이 fallback 카드를 내고 원문을 보존한다")
    void fallsBackOnUnparseableResponse() {
        String input = "컨테이너가 자꾸 죽어서 로그를 봤더니 exit 137";
        Map<String, Object> out = EXTRACTOR.extract(input, ctxWithResponse("[stub-llm-response]"));

        assertNotNull(out.get("title"));
        assertFalse(((String) out.get("title")).isBlank());
        // 원문 유실 금지 — 증상·요약에 원문을 남긴다.
        assertEquals(input, out.get("symptom"));
        assertEquals(input, out.get("summary"));
        assertEquals(List.of(), out.get("attempts"));
        assertEquals(List.of(), out.get("keywords"));
        // 해결됐다고 지어내지 않는다.
        assertEquals("UNRESOLVED", out.get("status"));
        assertEquals("", out.get("root_cause"));
        assertEquals("", out.get("final_solution"));
    }

    @Test
    @DisplayName("응답의 title이 비면 원문에서 제목을 파생한다(승인 시 memory.title 보장)")
    void derivesTitleWhenBlank() {
        Map<String, Object> out =
                EXTRACTOR.extract("원문제목후보", ctxWithResponse("{\"title\":\"\",\"symptom\":\"s\"}"));
        assertEquals("원문제목후보", out.get("title"));
    }

    @Test
    @DisplayName("🔴 ctx에 바인딩된 LlmClient만 호출된다 — 다른(남의) ctx의 클라이언트는 절대 안 건드림")
    void usesOnlyTheGivenCtxLlmClient() {
        LlmClient ownerClient = mock(LlmClient.class);
        when(ownerClient.complete(any(), any())).thenReturn("{\"title\":\"t\"}");
        LlmClient otherUsersClient = mock(LlmClient.class);

        EXTRACTOR.extract(
                "원문", new UserAiContext(1L, ownerClient, mock(EmbeddingClient.class), true, true));

        verify(ownerClient).complete(any(), any());
        verify(otherUsersClient, never()).complete(any(), any());
    }
}
