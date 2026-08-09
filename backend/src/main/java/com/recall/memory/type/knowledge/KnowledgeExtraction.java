package com.recall.memory.type.knowledge;

import com.recall.common.MemoryType;
import com.recall.memory.type.ExtractionStrategy;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 지식 유형 추출 전략 — <b>Phase 0 walking skeleton STUB</b>. 흐름이 걸어다니게 placeholder를 반환한다. Phase 1(지식 담당)에서
 * LLM으로 topic·facts·document 추출을 채운다.
 */
@Component
public class KnowledgeExtraction implements ExtractionStrategy {

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public Map<String, Object> extract(String maskedText) {
        // TODO(Phase 1): LLM으로 유형별 스키마 추출.
        return Map.of("title", "[stub] 지식 카드", "summary", maskedText, "facts", List.of());
    }
}
