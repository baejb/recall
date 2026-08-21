package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.PromptLoader;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.Verdict;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLM은 이제 생성자 주입 싱글턴이 아니라 매 호출 {@code ctx.requireChat()}로 얻으므로(사용자별 provider/키 교차유출 방지), 모든 호출은
 * ctx를 통해 클라이언트를 넘긴다.
 */
class KnowledgeJudgeTest {

    private static final Map<String, Object> PROPOSED = Map.of("title", "RRF", "document", "본문");
    private static final Map<String, Object> EXISTING = Map.of("title", "RRF", "document", "기존 본문");
    private static final KnowledgeJudge JUDGE = new KnowledgeJudge(new PromptLoader());

    private static UserAiContext ctxWithResponse(String response) {
        LlmClient fake = (system, user) -> response;
        return new UserAiContext(1L, fake, mock(EmbeddingClient.class), true, true);
    }

    @Test
    @DisplayName("supports()는 KNOWLEDGE")
    void supports() {
        assertEquals(Verdict.NEW, JUDGE.judge(PROPOSED, Map.of(), ctxWithResponse("{}")).verdict());
    }

    @Test
    @DisplayName("유사 후보가 없으면(existing 빈 맵) LLM 없이 NEW")
    void newWhenNoExisting() {
        Judgement j =
                JUDGE.judge(PROPOSED, Map.of(), ctxWithResponse("{\"verdict\":\"RECURRENCE\"}"));
        assertEquals(Verdict.NEW, j.verdict());
        assertNull(j.targetMemoryId());
    }

    @Test
    @DisplayName("정상 JSON 판정을 verdict·rationale로 매핑")
    void mapsValidJudgement() {
        Judgement j =
                JUDGE.judge(
                        PROPOSED,
                        EXISTING,
                        ctxWithResponse("{\"verdict\":\"RECURRENCE\",\"rationale\":\"같은 문제\"}"));
        assertEquals(Verdict.RECURRENCE, j.verdict());
        assertEquals("같은 문제", j.rationale());
        assertNull(j.targetMemoryId()); // 파이프라인이 채움
    }

    @Test
    @DisplayName("산문에 감싸인 JSON도 판정으로 추출")
    void extractsJsonFromProse() {
        Judgement j =
                JUDGE.judge(
                        PROPOSED,
                        EXISTING,
                        ctxWithResponse(
                                "판정 결과:\n{\"verdict\":\"CONFLICT\",\"rationale\":\"모순\"}\n이상"));
        assertEquals(Verdict.CONFLICT, j.verdict());
    }

    @Test
    @DisplayName("stub/깨진 응답이면 fallback(SUPPLEMENT — 사람 검토 유도)")
    void fallbackOnUnparseable() {
        Judgement j = JUDGE.judge(PROPOSED, EXISTING, ctxWithResponse("[stub-llm-response]"));
        assertEquals(Verdict.SUPPLEMENT, j.verdict());
    }

    @Test
    @DisplayName("알 수 없는 verdict 문자열도 fallback")
    void fallbackOnUnknownVerdict() {
        Judgement j =
                JUDGE.judge(
                        PROPOSED,
                        EXISTING,
                        ctxWithResponse("{\"verdict\":\"MAYBE\",\"rationale\":\"x\"}"));
        assertEquals(Verdict.SUPPLEMENT, j.verdict());
    }

    @Test
    @DisplayName("🔴 ctx에 바인딩된 LlmClient만 호출된다 — 다른(남의) ctx의 클라이언트는 절대 안 건드림")
    void usesOnlyTheGivenCtxLlmClientNotAnyOtherOne() {
        LlmClient ownerClient = mock(LlmClient.class);
        when(ownerClient.complete(any(), any()))
                .thenReturn("{\"verdict\":\"RECURRENCE\",\"rationale\":\"r\"}");
        LlmClient otherUsersClient = mock(LlmClient.class);

        UserAiContext ownerCtx =
                new UserAiContext(1L, ownerClient, mock(EmbeddingClient.class), true, true);

        JUDGE.judge(PROPOSED, EXISTING, ownerCtx);

        verify(ownerClient).complete(any(), any());
        verify(otherUsersClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("유사 후보가 없으면 ctx.requireChat()조차 호출하지 않는다(LLM 미설정이어도 안전)")
    void doesNotTouchChatWhenNoExisting() {
        UserAiContext chatNotConfigured =
                new UserAiContext(
                        1L, mock(LlmClient.class), mock(EmbeddingClient.class), false, true);

        Judgement j = JUDGE.judge(PROPOSED, Map.of(), chatNotConfigured);

        assertEquals(Verdict.NEW, j.verdict());
    }
}
