package com.recall.memory.type;

import com.recall.common.type.TypeStrategy;
import java.util.Map;

/**
 * P(Search Planner)의 유형별 기여 — 검색 대상 유형이 정해졌을 때 그 유형에 맞는 채널 가중치를 준다 (지식=vector 중심,
 * 트러블슈팅=exact·bm25·rerank 강). Planner 자체는 결정론적으로 유지되며(LLM 아님), 유형별 값만 이 SPI에서 가져온다.
 */
public interface PlanContribution extends TypeStrategy {

    /**
     * 채널 → 가중치. 예: {@code {MEMORY_BM25: 2.0, MEMORY_VECTOR: 1.2}}.
     *
     * <p>키가 {@link SearchChannel} enum 인 이유는 그 타입의 javadoc 에 있다 — 문자열 키였을 때 채널 이름 오타가 {@code
     * RrfFusion}의 {@code getOrDefault(1.0)}에 조용히 삼켜져 가중치 설계가 무효화됐다.
     */
    Map<SearchChannel, Double> channelWeights();
}
