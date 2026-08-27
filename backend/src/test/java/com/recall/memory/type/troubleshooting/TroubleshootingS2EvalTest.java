package com.recall.memory.type.troubleshooting;

import com.recall.common.prompt.PromptLoader;
import com.recall.llm.UserAiContext;
import com.recall.testsupport.EvalCases;
import com.recall.testsupport.LlmEvalSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 🔵 S2(트러블슈팅 추출) Eval — PRD §05 의 {@code 필드 ≥ 0.9, attempts 재현 ≥ 0.9} 를 라벨셋으로 채점한다. 라벨셋은 {@code
 * eval/s2-troubleshooting.json}.
 *
 * <p><b>왜 필드 값을 그대로 비교하지 않나</b> — 추출은 확률적이라 같은 사실도 표현이 매번 다르다("메모리 한도 초과" vs "heap 이 컨테이너 한도를 넘음").
 * 문자열 일치로 채점하면 모델이 옳게 답해도 빨개진다. 그래서 <b>구조적 성질</b>만 본다:
 *
 * <ul>
 *   <li><b>status</b> — 열거값이라 정확히 비교할 수 있다. Decision 13 의 비대칭(해결됐다고 잘못 단정 금지)이 실제로 지켜지는지가 여기 걸린다.
 *   <li><b>attempts 개수·실패 보존</b> — PRD 가 🟠 중대 실패로 꼽은 "실패 시도 유실"이다. 통한 시도만 남기고 실패를 버리는 추출을 잡는다.
 *   <li><b>에러 식별자 포함</b> — 시그니처나 에러 메시지에 exit 137·ClassNotFoundException 같은 토큰이 남아야 BM25 로 찾힌다.
 *   <li><b>원인·해결 유무</b> — 밝혀지지 않은 원인을 지어냈는지(근거 없는 생성), 반대로 있는 해결을 흘렸는지.
 * </ul>
 *
 * <p>실제 provider 를 호출하므로 {@code ./gradlew llmEval} 로만 돈다. 키가 없으면 스킵된다.
 */
@Tag("llm-eval")
class TroubleshootingS2EvalTest {

    /** PRD §05 S2 필드 정확도 임계. */
    private static final double THRESHOLD = 0.9;

    @Test
    @DisplayName("S2 추출 필드 정확도 ≥ 0.9 (status·attempts 보존·에러 식별자·원인/해결 유무)")
    void extractionFieldsMeetThreshold() {
        UserAiContext ctx = LlmEvalSupport.chatContext();
        TroubleshootingExtraction extraction = new TroubleshootingExtraction(new PromptLoader());

        List<Map<String, Object>> cases = EvalCases.load("eval/s2-troubleshooting.json");
        List<String> misses = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String id = EvalCases.str(c, "id");
            TroubleshootingCard card =
                    (TroubleshootingCard) extraction.extract(EvalCases.str(c, "input"), ctx);
            String problem = check(c, card);
            if (!problem.isEmpty()) {
                misses.add(id + ": " + problem);
            }
        }

        LlmEvalSupport.assertAccuracy("S2(트러블슈팅 추출)", cases.size(), misses, THRESHOLD);
    }

    /** 케이스의 기대 성질을 확인하고, 어긋난 것을 한 문장으로 돌려준다(빈 문자열 = 통과). */
    private static String check(Map<String, Object> c, TroubleshootingCard card) {
        List<String> problems = new ArrayList<>();

        String expectedStatus = EvalCases.str(c, "expectedStatus");
        if (!expectedStatus.equals(card.status())) {
            problems.add("status 기대 " + expectedStatus + " · 실제 " + card.status());
        }

        int minAttempts = intOf(c, "minAttempts");
        if (card.attempts().size() < minAttempts) {
            problems.add("attempts " + card.attempts().size() + "건 < 기대 " + minAttempts + "건");
        }

        int failedAtLeast = intOf(c, "failedAttemptsAtLeast");
        long failed =
                card.attempts().stream()
                        .filter(a -> TroubleshootingCard.Attempt.FAILED.equals(a.outcome()))
                        .count();
        if (failed < failedAtLeast) {
            problems.add("실패 시도 " + failed + "건 < 기대 " + failedAtLeast + "건(유실 의심)");
        }

        String errorHaystack = (card.errorSignature() + " " + card.errorMessage()).toLowerCase();
        for (String token : EvalCases.list(c, "mustMentionInSignatureOrError")) {
            if (!errorHaystack.contains(token.toLowerCase())) {
                problems.add("에러 식별자 '" + token + "' 가 시그니처·메시지에 없다");
            }
        }

        if (boolOf(c, "mustHaveRootCause") && card.rootCause().isBlank()) {
            problems.add("근본 원인이 비었다");
        }
        if (!boolOf(c, "mustHaveRootCause") && !card.rootCause().isBlank()) {
            problems.add("밝혀지지 않은 원인을 채웠다: " + card.rootCause());
        }
        if (boolOf(c, "mustHaveSolution") && card.finalSolution().isBlank()) {
            problems.add("최종 해결이 비었다");
        }
        if (!boolOf(c, "mustHaveSolution") && !card.finalSolution().isBlank()) {
            problems.add("해결되지 않았는데 해결을 채웠다: " + card.finalSolution());
        }

        return String.join(" / ", problems);
    }

    private static int intOf(Map<String, Object> c, String key) {
        Object v = c.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static boolean boolOf(Map<String, Object> c, String key) {
        return Boolean.TRUE.equals(c.get(key));
    }
}
