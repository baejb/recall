package com.recall.capture.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.recall.testsupport.EvalCases;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * M0 마스킹이 <b>아직 가리지 못하는</b> 모양의 라벨셋({@code eval/masking-gaps.json}).
 *
 * <p><b>왜 실패하는 테스트를 남겨 두나</b> — Eval 셋을 만들면서 발견한 유출 모양(Basic 인증 헤더·접속 문자열의 비밀번호·웹훅 URL·구분자 없는 한국어
 * 문장형·키 이름 없는 고엔트로피 값·개인정보)을 문서 한 줄로 적어두면 사라진다. 케이스로 두면 <b>고칠 때 검증 수단이 이미 있다</b>. 각 케이스의 {@code
 * why} 필드에 왜 지금 안 하는지가 적혀 있고(대개 거짓 양성 위험이나 제품 결정), 자세한 판단은 {@code docs/eval.md}.
 *
 * <p>{@link Disabled} 인 이유: 지금 켜면 🔴 게이트가 상시 빨개져서 게이트가 신호를 잃는다. 패턴을 고치면 이 애노테이션을 지우고 케이스를 {@code
 * masking-m0.json} 으로 옮긴다 — 그 diff 가 "커버 범위를 넓혔다"는 기록이 된다.
 */
@Disabled("미구현 커버리지 — docs/eval.md '마스킹 미커버 모양' 참조. 고치면 케이스를 masking-m0.json 으로 옮긴다")
class MaskingGapEvalTest {

    private static final MaskingService MASKING = new MaskingService();

    static Stream<Map<String, Object>> gaps() {
        return EvalCases.load("eval/masking-gaps.json").stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("gaps")
    @DisplayName("미커버 유출 모양 — 구현되면 이 테스트를 켠다")
    void knownGaps(Map<String, Object> testCase) {
        String masked = MASKING.mask(EvalCases.str(testCase, "input")).maskedText();

        for (String secret : EvalCases.list(testCase, "secrets")) {
            assertFalse(
                    masked.contains(secret),
                    () ->
                            "["
                                    + EvalCases.str(testCase, "id")
                                    + "] 미커버: "
                                    + secret
                                    + "\n이유: "
                                    + EvalCases.str(testCase, "why"));
        }
    }
}
