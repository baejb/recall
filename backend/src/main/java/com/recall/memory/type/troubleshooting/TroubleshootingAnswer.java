package com.recall.memory.type.troubleshooting;

import com.recall.common.MemoryType;
import com.recall.memory.type.AnswerContribution;
import java.util.List;
import java.util.Map;
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
    public String render(Map<String, Object> memory) {
        StringBuilder sb = new StringBuilder();

        String title = str(memory.get("title"));
        String summary = str(memory.get("summary"));
        if (!title.isBlank()) {
            sb.append(title);
        }
        if (!summary.isBlank()) {
            sb.append(sb.isEmpty() ? "" : " — ").append(summary);
        }

        append(sb, "증상", str(memory.get("symptom")));
        append(sb, "에러", errorLine(memory));
        append(sb, "환경", str(memory.get("environment")));
        append(sb, "시도", attempts(memory.get("attempts")));
        append(sb, "원인", str(memory.get("root_cause")));
        append(sb, "해결", str(memory.get("final_solution")));
        append(sb, "상태", str(memory.get("status")));

        return sb.isEmpty() ? "(내용 없음)" : sb.toString();
    }

    /** 에러는 시그니처를 앞세우고, 원문 조각이 따로 있으면 함께 남긴다(정확 토큰과 맥락 둘 다 근거가 된다). */
    private String errorLine(Map<String, Object> memory) {
        String signature = str(memory.get("error_signature"));
        String message = str(memory.get("error_message"));
        if (signature.isBlank()) {
            return message;
        }
        return message.isBlank() || message.equals(signature)
                ? signature
                : signature + " | " + message;
    }

    /** 시도 이력 — {@code 조치 → 결과 (판정)}을 가운뎃점으로 잇는다. 실패 판정도 그대로 노출한다. */
    private String attempts(Object attempts) {
        if (!(attempts instanceof List<?> list)) {
            return "";
        }
        return list.stream()
                .map(this::attemptLine)
                .filter(line -> !line.isBlank())
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
    }

    private String attemptLine(Object attempt) {
        if (!(attempt instanceof Map<?, ?> map)) {
            return str(attempt);
        }
        String action = str(map.get("action"));
        String result = str(map.get("result"));
        String outcome = str(map.get("outcome"));
        if (action.isBlank() && result.isBlank()) {
            return "";
        }
        String joined =
                Stream.of(action, result)
                        .filter(value -> !value.isBlank())
                        .reduce((a, b) -> a + " → " + b)
                        .orElse("");
        return outcome.isBlank() ? joined : joined + " (" + outcome + ")";
    }

    private void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(INDENT).append(label).append(": ").append(value);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }
}
