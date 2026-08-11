package com.recall.memory.type.knowledge;

import com.recall.common.MemoryType;
import com.recall.memory.type.PlanContribution;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 지식(knowledge) 유형 검색 계획 기여(P) — 채널 가중치를 준다. PRD: 지식은 <b>vector 중심 · BM25 보조</b>이므로 vector 채널에 더 큰
 * 가중치를 둔다. (값의 근거는 라벨셋 fit — 튜닝 시 커밋에 근거를 남긴다.)
 */
@Component
public class KnowledgePlanContribution implements PlanContribution {

    /** 의미 유사(벡터)가 주 채널. */
    private static final double MEMORY_VECTOR_WEIGHT = 2.0;

    /** 정확한 단어 일치(BM25)는 보조 채널. */
    private static final double MEMORY_BM25_WEIGHT = 1.0;

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public Map<String, Double> channelWeights() {
        return Map.of(
                "memory_vector", MEMORY_VECTOR_WEIGHT,
                "memory_bm25", MEMORY_BM25_WEIGHT);
    }
}
