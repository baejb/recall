package com.recall.memory.type.knowledge;

import com.recall.common.type.MemoryType;
import com.recall.memory.type.AnswerContribution;
import com.recall.memory.type.MemoryCard;
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
    public String render(MemoryCard card) {
        // 레지스트리가 memory.type 으로 디스패치하므로 정상 흐름에서 카드 타입은 반드시 일치한다.
        // 어긋났다면 배선 버그이므로 조용히 다른 유형처럼 렌더하지 않고 즉시 드러낸다(조용한 실패 금지).
        KnowledgeCard kn = requireKnowledge(card);
        StringBuilder sb = new StringBuilder();

        if (!kn.title().isBlank()) {
            sb.append(kn.title());
        }
        if (!kn.summary().isBlank()) {
            sb.append(sb.isEmpty() ? "" : " — ").append(kn.summary());
        }

        String facts = joinFacts(kn);
        if (!facts.isBlank()) {
            sb.append(INDENT).append("사실: ").append(facts);
        }

        return sb.isEmpty() ? "(내용 없음)" : sb.toString();
    }

    private static KnowledgeCard requireKnowledge(MemoryCard card) {
        if (card instanceof KnowledgeCard kn) {
            return kn;
        }
        throw new IllegalArgumentException(
                "KNOWLEDGE 전략에 다른 유형 카드가 전달됨: "
                        + (card == null ? "null" : card.getClass().getSimpleName()));
    }

    /** 사실 항목을 가운뎃점으로 잇는다(공유 프롬프트가 쓰던 형식과 동일). */
    private String joinFacts(KnowledgeCard kn) {
        return kn.facts().stream()
                .map(fact -> fact == null ? "" : fact.strip())
                .filter(fact -> !fact.isBlank())
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
    }
}
