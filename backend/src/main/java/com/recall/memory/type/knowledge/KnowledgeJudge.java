package com.recall.memory.type.knowledge;

import com.recall.common.MemoryType;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.SimilarityJudgeStrategy;
import com.recall.memory.type.Verdict;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 지식 유형 판정 전략 — <b>Phase 0 walking skeleton STUB</b>. 항상 신규(NEW)로 간주한다. Phase 1(지식 담당)에서 fact 대조로
 * 신규/재발/보완/충돌 판정을 채운다.
 */
@Component
public class KnowledgeJudge implements SimilarityJudgeStrategy {

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public Judgement judge(Map<String, Object> proposed, Map<String, Object> existing) {
        // TODO(Phase 1): 유사 memory와 fact 대조.
        return new Judgement(Verdict.NEW, null, "[stub] 신규로 간주");
    }
}
