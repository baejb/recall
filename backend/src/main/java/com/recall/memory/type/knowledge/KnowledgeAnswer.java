package com.recall.memory.type.knowledge;

import com.recall.common.MemoryType;
import com.recall.memory.type.AnswerContribution;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 지식 유형 답변 기여 — <b>흐름 검증용 STUB</b>. 지금은 요약을 그대로 조각으로 낸다. Phase 1(지식 담당)에서 intent별 근거·필드 조합을 채운다.
 */
@Component
public class KnowledgeAnswer implements AnswerContribution {

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public String render(Map<String, Object> memory) {
        Object summary = memory.get("summary");
        return summary == null ? "(내용 없음)" : summary.toString();
    }
}
