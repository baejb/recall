package com.recall.search.service;

import com.recall.memory.type.SearchChannel;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion — 여러 검색 채널의 순위 리스트를 채널 가중치로 결합한다. 결정론 단계(불변 원칙: 융합에 LLM 금지)라 순수 함수로 둔다.
 *
 * <p>각 채널의 rank r(0-base)에 대해 {@code weight / (K + r + 1)} 를 memory별로 더하고, 점수 내림차순(동점은 id 오름차순)으로
 * 정렬한다. 동점 tie-break를 두어 같은 입력이면 항상 같은 순서를 낸다.
 */
public final class RrfFusion {

    /** RRF 완충 상수(관례적 60). 큰 값일수록 상위 순위 간 점수 차가 완만해진다. */
    public static final int K = 60;

    private RrfFusion() {}

    /**
     * @param rankedByChannel 채널 → 순위 정렬된 memory id 리스트(앞이 상위)
     * @param weights 채널 → 가중치(없으면 1.0). 키가 enum 이라 채널 이름 오타로 가중치가 조용히 무시되는 일이 없다
     * @return 융합 점수 내림차순 memory id 리스트
     */
    public static List<Long> fuse(
            Map<SearchChannel, List<Long>> rankedByChannel, Map<SearchChannel, Double> weights) {
        Map<Long, Double> scores = new HashMap<>();
        for (Map.Entry<SearchChannel, List<Long>> channel : rankedByChannel.entrySet()) {
            double weight = weights.getOrDefault(channel.getKey(), 1.0);
            List<Long> ranked = channel.getValue();
            for (int rank = 0; rank < ranked.size(); rank++) {
                scores.merge(ranked.get(rank), weight / (K + rank + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(
                        Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue)
                                .reversed()
                                .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }
}
