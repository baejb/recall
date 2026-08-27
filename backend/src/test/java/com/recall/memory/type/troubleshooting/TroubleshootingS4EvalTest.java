package com.recall.memory.type.troubleshooting;

import com.recall.common.prompt.PromptLoader;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.Verdict;
import com.recall.testsupport.EvalCases;
import com.recall.testsupport.LlmEvalSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 🔵 S4(트러블슈팅 판정) Eval — PRD §05 의 {@code 정확도 ≥ 0.85, silent overwrite = 0} 을 라벨셋으로 채점한다. 라벨셋은
 * {@code eval/s4-judgement.json}.
 *
 * <p>케이스는 판정이 <b>표면 유사도로 갈리지 않는지</b>를 노린다: 같은 에러 시그니처인데 근본 원인이 다르면 NEW, 같은 문제에 서로 배타적인 해결책이면
 * CONFLICT, 같은 에러라도 환경이 다르면 재발이 아닐 수 있다. 시그니처만 보는 판정은 이 셋에서 무너진다.
 *
 * <p>{@code silent overwrite = 0} 은 별도로 본다 — CONFLICT 케이스가 CONFLICT 보다 약한
 * 판정(RECURRENCE·SUPPLEMENT)으로 내려가면 두 기록 중 하나가 조용히 덮일 수 있으므로, 정확도와 무관하게 그 자체를 실패로 취급한다.
 *
 * <p>실제 provider 를 호출하므로 {@code ./gradlew llmEval} 로만 돈다. 키가 없으면 스킵된다.
 */
@Tag("llm-eval")
class TroubleshootingS4EvalTest {

    /** PRD §05 S4 판정 정확도 임계. */
    private static final double THRESHOLD = 0.85;

    @Test
    @DisplayName("S4 판정 정확도 ≥ 0.85 + CONFLICT 격하 0건 (PRD §05)")
    void judgementAccuracyMeetsThreshold() {
        UserAiContext ctx = LlmEvalSupport.chatContext();
        TroubleshootingJudge judge = new TroubleshootingJudge(new PromptLoader());

        List<Map<String, Object>> cases = EvalCases.load("eval/s4-judgement.json");
        List<String> misses = new ArrayList<>();
        List<String> downgradedConflicts = new ArrayList<>();

        for (Map<String, Object> c : cases) {
            Verdict expected = Verdict.valueOf(EvalCases.str(c, "expectedVerdict"));
            Judgement judgement = judge.judge(card(c, "proposed"), card(c, "existing"), ctx);

            if (expected != judgement.verdict()) {
                misses.add(
                        EvalCases.str(c, "id")
                                + ": 기대 "
                                + expected
                                + " · 실제 "
                                + judgement.verdict()
                                + " ("
                                + judgement.rationale()
                                + ")");
                // 충돌을 못 알아본 것은 정확도와 별개로 🔴 이다 — 두 기록 중 하나가 조용히 덮일 수 있다.
                if (expected == Verdict.CONFLICT) {
                    downgradedConflicts.add(EvalCases.str(c, "id") + " → " + judgement.verdict());
                }
            }
        }

        if (!downgradedConflicts.isEmpty()) {
            throw new AssertionError(
                    "CONFLICT 를 약한 판정으로 격하했다(자동 덮어쓰기 위험): "
                            + String.join(", ", downgradedConflicts));
        }
        LlmEvalSupport.assertAccuracy("S4(트러블슈팅 판정)", cases.size(), misses, THRESHOLD);
    }

    /** 케이스의 proposed/existing 접두 필드로 카드를 만든다. 제목·요약은 판정 근거가 아니라 비워 둔다. */
    private static TroubleshootingCard card(Map<String, Object> c, String side) {
        return new TroubleshootingCard(
                "",
                "",
                List.of(),
                EvalCases.str(c, side + "Symptom"),
                "",
                EvalCases.str(c, side + "Signature"),
                EvalCases.str(c, side + "Environment"),
                List.of(),
                EvalCases.str(c, side + "RootCause"),
                EvalCases.str(c, side + "Solution"),
                TroubleshootingCard.UNRESOLVED);
    }
}
