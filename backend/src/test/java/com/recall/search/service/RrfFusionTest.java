package com.recall.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.memory.type.SearchChannel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

    @Test
    @DisplayName("두 채널에 모두 상위인 id가 가장 높은 융합 점수를 받는다")
    void bothChannelsTopWins() {
        Map<SearchChannel, List<Long>> ranked =
                Map.of(
                        SearchChannel.MEMORY_VECTOR, List.of(1L, 2L, 3L),
                        SearchChannel.MEMORY_BM25, List.of(2L, 1L, 4L));
        List<Long> fused =
                RrfFusion.fuse(
                        ranked,
                        Map.of(SearchChannel.MEMORY_VECTOR, 1.0, SearchChannel.MEMORY_BM25, 1.0));

        // 1,2는 두 채널 모두 상위 → 3,4보다 앞. 동점(1,2)은 id 오름차순 tie-break.
        assertEquals(List.of(1L, 2L, 3L, 4L), fused);
    }

    @Test
    @DisplayName("채널 가중치가 큰 쪽의 상위가 융합 순위를 끌어올린다")
    void weightBiasesRanking() {
        Map<SearchChannel, List<Long>> ranked =
                Map.of(
                        SearchChannel.MEMORY_VECTOR, List.of(10L),
                        SearchChannel.MEMORY_BM25, List.of(20L));
        // vector 가중치를 크게 → 10L이 20L보다 앞
        List<Long> fused =
                RrfFusion.fuse(
                        ranked,
                        Map.of(SearchChannel.MEMORY_VECTOR, 5.0, SearchChannel.MEMORY_BM25, 1.0));
        assertEquals(List.of(10L, 20L), fused);
    }

    // "같은 입력이면 같은 결과"를 순수 함수를 5번 호출해 확인하던 테스트가 있었는데 지웠다 — 부작용이 없는
    // 함수가 같은 값을 낸다는 건 검증이 아니라 동어반복이고, 실제 결정론을 지켜 주는 장치(동점 tie-break)는
    // bothChannelsTopWins 가 이미 고정한다(1·2 동점 → id 오름차순).

    @Test
    @DisplayName("빈 채널이어도 예외 없이 빈 결과")
    void emptyChannels() {
        assertTrue(
                RrfFusion.fuse(
                                Map.of(
                                        SearchChannel.MEMORY_VECTOR,
                                        List.of(),
                                        SearchChannel.MEMORY_BM25,
                                        List.of()),
                                Map.of())
                        .isEmpty());
    }
}
