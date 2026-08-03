package com.recall.memory.type;

import com.recall.common.TypeStrategy;
import java.util.Map;

/**
 * P(Search Planner)의 유형별 기여 — 검색 대상 유형이 정해졌을 때 그 유형에 맞는 채널 가중치를 준다 (지식=vector 중심,
 * 트러블슈팅=exact·bm25·rerank 강). Planner 자체는 결정론적으로 유지되며(LLM 아님), 유형별 값만 이 SPI에서 가져온다.
 */
public interface PlanContribution extends TypeStrategy {

    /** 채널명 → 가중치. 예: {@code {"exact": 3.0, "raw_bm25": 2.0, "memory_vector": 1.2}}. */
    Map<String, Double> channelWeights();
}
