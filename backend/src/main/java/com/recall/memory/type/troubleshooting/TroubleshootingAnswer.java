package com.recall.memory.type.troubleshooting;

import com.recall.common.type.MemoryType;
import com.recall.memory.type.AnswerContribution;
import com.recall.memory.type.MemoryCard;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 트러블슈팅(troubleshooting) 유형 답변 기여(A) — 저장된 카드에서 <b>근거 조각만</b> 만든다. 번호·질문·intent별 재구성은 공유
 * Composer({@code QueryPipeline})가 하고, 이 전략은 "이 유형은 무엇을 근거로 내놓아야 하는가"만 답한다(architecture.md 가드레일 2).
 *
 * <p>증상만 주면 "결국 어떻게 고쳤는지" 질문에 답할 수 없고, 해결만 주면 "뭘 시도했었지" 질문에 답할 수 없다. 그래서 증상·에러·환경·시도·원인·해결·상태를 모두
 * 싣는다. 특히 <b>실패한 시도도 결과·판정과 함께</b> 남긴다 — PRD가 attempts 유실을 🟠 중대 실패로 두는 이유가 이 회상이다.
 *
 * <p>값이 없는 필드는 라벨조차 렌더하지 않는다. 빈 값을 근거처럼 보여 LLM이 그 자리를 상상으로 채우게 만들지 않기 위해서다(🔴 근거 없는 생성 금지).
 */
@Component
public class TroubleshootingAnswer implements AnswerContribution {

    /** 근거 줄 앞 들여쓰기 — 공유 Composer가 붙이는 {@code [n]} 번호 밑에 붙는 세부 줄. */
    private static final String INDENT = "\n    ";

    @Override
    public MemoryType supports() {
        return MemoryType.TROUBLESHOOTING;
    }

    @Override
    public String render(MemoryCard card) {
        // 레지스트리가 memory.type 으로 디스패치하므로 정상 흐름에서 카드 타입은 반드시 일치한다.
        // 어긋났다면 배선 버그이므로 조용히 다른 유형처럼 렌더하지 않고 즉시 드러낸다(조용한 실패 금지).
        TroubleshootingCard ts = requireTroubleshooting(card);
        StringBuilder sb = new StringBuilder();

        if (!ts.title().isBlank()) {
            sb.append(ts.title());
        }
        if (!ts.summary().isBlank()) {
            sb.append(sb.isEmpty() ? "" : " — ").append(ts.summary());
        }

        // 카드 접근자로 읽는다 — 전엔 memory.get("root_cause") 처럼 필드 이름을 문자열로 다시 적어서,
        // 스키마가 바뀌어도 컴파일 에러 없이 근거가 조용히 비었다.
        append(sb, "증상", ts.symptom());
        append(sb, "에러", errorLine(ts));
        append(sb, "환경", ts.environment());
        append(sb, "시도", attempts(ts));
        append(sb, "원인", ts.rootCause());
        append(sb, "해결", ts.finalSolution());
        append(sb, "상태", ts.status());

        return sb.isEmpty() ? "(내용 없음)" : sb.toString();
    }

    private static TroubleshootingCard requireTroubleshooting(MemoryCard card) {
        if (card instanceof TroubleshootingCard ts) {
            return ts;
        }
        throw new IllegalArgumentException(
                "TROUBLESHOOTING 전략에 다른 유형 카드가 전달됨: "
                        + (card == null ? "null" : card.getClass().getSimpleName()));
    }

    /** 에러는 시그니처를 앞세우고, 원문 조각이 따로 있으면 함께 남긴다(정확 토큰과 맥락 둘 다 근거가 된다). */
    private String errorLine(TroubleshootingCard ts) {
        String signature = ts.errorSignature();
        String message = ts.errorMessage();
        if (signature.isBlank()) {
            return message;
        }
        return message.isBlank() || message.equals(signature)
                ? signature
                : signature + " | " + message;
    }

    /** 시도 이력 — {@code 조치 → 결과 (판정)}을 가운뎃점으로 잇는다. 실패 판정도 그대로 노출한다. */
    private String attempts(TroubleshootingCard ts) {
        return ts.attempts().stream()
                .map(this::attemptLine)
                .filter(line -> !line.isBlank())
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
    }

    private String attemptLine(TroubleshootingCard.Attempt attempt) {
        // Attempt 가 타입이라 instanceof Map<?,?> 방어가 필요 없다 — 값 정규화는 record 생성자가 이미 했다.
        if (attempt == null) {
            return "";
        }
        if (attempt.action().isBlank() && attempt.result().isBlank()) {
            return "";
        }
        String joined =
                Stream.of(attempt.action(), attempt.result())
                        .filter(value -> !value.isBlank())
                        .reduce((a, b) -> a + " → " + b)
                        .orElse("");
        return attempt.outcome().isBlank() ? joined : joined + " (" + attempt.outcome() + ")";
    }

    private void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(INDENT).append(label).append(": ").append(value);
        }
    }
}
