package com.recall.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

    @Test
    @DisplayName("두 채널에 모두 상위인 id가 가장 높은 융합 점수를 받는다")
    void bothChannelsTopWins() {
        Map<String, List<Long>> ranked =
                Map.of(
                        "memory_vector", List.of(1L, 2L, 3L),
                        "memory_bm25", List.of(2L, 1L, 4L));
        List<Long> fused = RrfFusion.fuse(ranked, Map.of("memory_vector", 1.0, "memory_bm25", 1.0));

        // 1,2는 두 채널 모두 상위 → 3,4보다 앞. 동점(1,2)은 id 오름차순 tie-break.
        assertEquals(List.of(1L, 2L, 3L, 4L), fused);
    }

    @Test
    @DisplayName("채널 가중치가 큰 쪽의 상위가 융합 순위를 끌어올린다")
    void weightBiasesRanking() {
        Map<String, List<Long>> ranked =
                Map.of(
                        "memory_vector", List.of(10L),
                        "memory_bm25", List.of(20L));
        // vector 가중치를 크게 → 10L이 20L보다 앞
        List<Long> fused = RrfFusion.fuse(ranked, Map.of("memory_vector", 5.0, "memory_bm25", 1.0));
        assertEquals(List.of(10L, 20L), fused);
    }

    @Test
    @DisplayName("같은 입력은 항상 같은 순서를 낸다(결정론)")
    void deterministic() {
        Map<String, List<Long>> ranked =
                Map.of("memory_vector", List.of(1L, 2L), "memory_bm25", List.of(3L, 4L));
        Map<String, Double> weights = Map.of("memory_vector", 1.0, "memory_bm25", 1.0);
        List<Long> first = RrfFusion.fuse(ranked, weights);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, RrfFusion.fuse(ranked, weights));
        }
    }

    @Test
    @DisplayName("빈 채널이어도 예외 없이 빈 결과")
    void emptyChannels() {
        assertTrue(
                RrfFusion.fuse(
                                Map.of("memory_vector", List.of(), "memory_bm25", List.of()),
                                Map.of())
                        .isEmpty());
    }
}
