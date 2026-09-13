package com.recall.capture.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.testsupport.EvalCases;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 🔴 M0 마스킹 Eval — <b>시크릿 잔존 0</b>(PRD §06 치명 실패 1번, §7.2 "M0=민감패턴 잔존 0"). 라벨셋은 {@code
 * eval/masking-m0.json}.
 *
 * <p><b>단위테스트와 무엇이 다른가</b> — {@code MaskingServiceTest} 는 규칙 하나하나의 동작(치환 결과·멱등·span 기록)을 고정한다. 이
 * Eval 은 <b>커버 범위</b>를 고정한다: 실제로 붙여넣어질 만한 텍스트에서 시크릿이 한 조각도 남지 않는지. 그래서 케이스는 코드가 아니라 데이터고, 새 유출 모양을
 * 발견하면 JSON 에 한 줄 추가하는 것이 이 게이트를 넓히는 방법이다.
 *
 * <p>채점은 두 축이다. (1) <b>잔존 0</b> — 케이스의 {@code secrets} 조각이 마스킹 결과에 하나도 없어야 한다. 이게 🔴 이고, 실패하면 병합을
 * 막는다. (2) <b>종류 표기</b> — {@code expect} 에 적힌 플레이스홀더가 등장해야 한다. 값이 가려지기만 하고 종류가 어긋나면 검토 화면이 무엇을 가렸는지
 * 잘못 알려주므로 함께 본다(부수적 축이라 잔존 0 뒤에 검사한다).
 *
 * <p>아직 못 가리는 모양은 {@code eval/masking-gaps.json} 에 케이스로 남아 있다 — {@link MaskingGapEvalTest} 참조.
 */
@Tag("release-gate")
class MaskingEvalTest {

    private static final MaskingService MASKING = new MaskingService();

    static Stream<Map<String, Object>> cases() {
        return EvalCases.load("eval/masking-m0.json").stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    @DisplayName("🔴 마스킹 후 시크릿 잔존 0 + 종류 표기 일치")
    void secretsNeverSurvive(Map<String, Object> testCase) {
        String id = EvalCases.str(testCase, "id");
        String input = EvalCases.str(testCase, "input");
        List<String> secrets = EvalCases.list(testCase, "secrets");
        List<String> expected = EvalCases.list(testCase, "expect");

        String masked = MASKING.mask(input).maskedText();

        // (1) 🔴 잔존 0 — 조각 하나라도 남으면 그 값은 외부 LLM·인덱스·로그로 나간다.
        for (String secret : secrets) {
            assertFalse(
                    masked.contains(secret),
                    () -> "[" + id + "] 시크릿이 마스킹 후에도 남았다: " + secret + "\n결과: " + masked);
        }

        // (2) 종류 표기 — 가렸다면 무엇을 가렸는지도 맞아야 한다(검토 화면이 이 값을 보여준다).
        for (String placeholder : expected) {
            assertTrue(
                    masked.contains("⟨" + placeholder + "⟩"),
                    () -> "[" + id + "] 기대한 플레이스홀더 ⟨" + placeholder + "⟩ 가 없다\n결과: " + masked);
        }

        // 음성 케이스(시크릿 없음)는 원문이 그대로여야 한다 — 과잉 마스킹은 원문을 못 읽게 만든다.
        if (secrets.isEmpty() && expected.isEmpty()) {
            assertEquals(input, masked, () -> "[" + id + "] 가릴 것이 없는데 원문이 바뀌었다(거짓 양성)");
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    @DisplayName("🔴 멱등 — 두 번 마스킹해도 결과가 같다(재마스킹으로 원문이 망가지지 않는다)")
    void maskingIsIdempotent(Map<String, Object> testCase) {
        String input = EvalCases.str(testCase, "input");

        String once = MASKING.mask(input).maskedText();
        String twice = MASKING.mask(once).maskedText();

        assertEquals(once, twice, () -> "[" + EvalCases.str(testCase, "id") + "] 재마스킹이 결과를 바꿨다");
    }
}
