package com.recall.memory.type.knowledge;

import com.recall.common.MemoryType;
import com.recall.memory.type.AnswerContribution;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 지식(knowledge) 유형 답변 기여(A) — 저장된 카드에서 <b>근거 조각만</b> 만든다(제목·요약·사실). 번호·질문·intent별 재구성은 공유
 * Composer({@code QueryPipeline})가 맡는다.
 *
 * <p>이 조각은 원래 공유 답변 프롬프트에 knowledge 필드(title/summary/facts)로 하드코딩돼 있었다. 유형이 늘면 공유 코드를 계속 고쳐야 하므로
 * (OCP 붕괴) 유형별 전략으로 옮겼다 — architecture.md 가드레일 2("유형 전략은 근거·필드만 기여").
 *
 * <p>값이 없는 필드는 라벨조차 렌더하지 않는다(빈 자리를 LLM이 상상으로 채우지 않게 — 🔴 근거 없는 생성 금지).
 */
@Component
public class KnowledgeAnswer implements AnswerContribution {

    /** 근거 줄 앞 들여쓰기 — 공유 Composer가 붙이는 {@code [n]} 번호 밑에 붙는 세부 줄. */
    private static final String INDENT = "\n    ";

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
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

        String facts = facts(memory.get("facts"));
        if (!facts.isBlank()) {
            sb.append(INDENT).append("사실: ").append(facts);
        }

        return sb.isEmpty() ? "(내용 없음)" : sb.toString();
    }

    /** 사실 항목을 가운뎃점으로 잇는다(공유 프롬프트가 쓰던 형식과 동일). */
    private String facts(Object facts) {
        if (!(facts instanceof List<?> list)) {
            return "";
        }
        return list.stream()
                .map(KnowledgeAnswer::str)
                .filter(fact -> !fact.isBlank())
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }
}
