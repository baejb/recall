package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.Verdict;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** S4 판정의 결정론 부분(응답 파싱·verdict 방어·fallback)을 검증한다. KnowledgeJudgeTest와 같은 구성. */
class TroubleshootingJudgeTest {

    private static final TroubleshootingJudge JUDGE = new TroubleshootingJudge(new PromptLoader());

    private static final Map<String, Object> PROPOSED =
            Map.of("title", "컨테이너 OOM", "error_signature", "OOMKilled exit 137");
    private static final Map<String, Object> EXISTING =
            Map.of("title", "컨테이너가 죽음", "error_signature", "OOMKilled exit 137");

    private static UserAiContext ctxWithResponse(String response) {
        LlmClient fake = (system, user) -> response;
        return new UserAiContext(1L, fake, mock(EmbeddingClient.class), true, true);
    }

    @Test
    @DisplayName("supports()는 TROUBLESHOOTING")
    void supports() {
        assertEquals(MemoryType.TROUBLESHOOTING, JUDGE.supports());
    }

    @Test
    @DisplayName("유사 후보가 없으면 LLM 없이 NEW")
    void newWithoutLlmWhenNoExisting() {
        LlmClient never = mock(LlmClient.class);
        UserAiContext ctx = new UserAiContext(1L, never, mock(EmbeddingClient.class), true, true);

        Judgement j = JUDGE.judge(PROPOSED, Map.of(), ctx);

        assertEquals(Verdict.NEW, j.verdict());
        assertNull(j.targetMemoryId(), "targetMemoryId는 파이프라인이 채운다");
        verify(never, never()).complete(any(), any());
    }

    @Test
    @DisplayName("verdict 4종을 그대로 판정으로 옮긴다")
    void parsesEachVerdict() {
        for (Verdict expected : Verdict.values()) {
            String response = "{\"verdict\":\"" + expected.name() + "\",\"rationale\":\"같은 시그니처\"}";
            Judgement j = JUDGE.judge(PROPOSED, EXISTING, ctxWithResponse(response));
            assertEquals(expected, j.verdict());
            assertEquals("같은 시그니처", j.rationale());
        }
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 CONFLICT 판정은 그대로 보존한다 — 판정 단계가 임의로 낮추지 않는다(자동 덮어쓰기 금지)")
    void conflictSurvivesJudgement() {
        String response = "{\"verdict\":\"CONFLICT\",\"rationale\":\"같은 에러인데 해결책이 서로 배타적\"}";

        Judgement j = JUDGE.judge(PROPOSED, EXISTING, ctxWithResponse(response));

        assertEquals(Verdict.CONFLICT, j.verdict());
        assertTrue(j.rationale().contains("배타적"), "충돌 근거가 검토 화면까지 전달돼야 한다");
    }

    @Test
    @DisplayName("모르는 verdict는 fallback(SUPPLEMENT + 사람 검토 유도)")
    void unknownVerdictFallsBack() {
        Judgement j =
                JUDGE.judge(PROPOSED, EXISTING, ctxWithResponse("{\"verdict\":\"MAYBE_SAME\"}"));

        assertEquals(Verdict.SUPPLEMENT, j.verdict());
        assertTrue(j.rationale().contains("사람 검토"), "조용히 삼키지 않고 이유를 남긴다");
    }

    @Test
    @DisplayName("JSON이 없는 응답(stub 포함)은 fallback")
    void unparseableFallsBack() {
        Judgement j = JUDGE.judge(PROPOSED, EXISTING, ctxWithResponse("[stub-llm-response]"));
        assertEquals(Verdict.SUPPLEMENT, j.verdict());
    }

    @Test
    @DisplayName("설정 완료 후 외부 LLM 호출 실패도 fallback (NEW로 새로 만들지 않는다)")
    void llmFailureFallsBack() {
        UserAiContext boom =
                new UserAiContext(
                        1L,
                        (system, user) -> {
                            throw new RuntimeException("external boom");
                        },
                        mock(EmbeddingClient.class),
                        true,
                        true);

        Judgement j = JUDGE.judge(PROPOSED, EXISTING, boom);

        assertEquals(Verdict.SUPPLEMENT, j.verdict());
    }

    @Test
    @DisplayName("rationale이 비면 (근거 없음)으로 채운다")
    void blankRationaleIsMarked() {
        Judgement j =
                JUDGE.judge(
                        PROPOSED,
                        EXISTING,
                        ctxWithResponse("{\"verdict\":\"RECURRENCE\",\"rationale\":\"\"}"));

        assertEquals(Verdict.RECURRENCE, j.verdict());
        assertEquals("(근거 없음)", j.rationale());
    }
}
