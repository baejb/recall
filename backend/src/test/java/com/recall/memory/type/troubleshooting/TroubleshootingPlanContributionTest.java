package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.memory.type.SearchChannel;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P(플래너) 채널 가중치 — 결정론이라 값까지 고정한다. 이 값은 Decision Log 에 근거를 남긴 선택이라(PRD §2.2 예시값) 바뀔 때 눈에 띄어야 한다.
 *
 * <p>이 클래스는 {@code TroubleshootingStrategyCoverageTest} 를 대체한다. 그 클래스에는 신호가 없는 테스트가 있었다: 손으로 적은 5개
 * 리스트의 {@code size()} 가 5인지 확인하고(테스트가 자기 리터럴을 검증), 각 전략의 {@code supports()} 가 자기 유형을 반환하는지 확인했다 (한
 * 줄 return 의 위임). SPI 누락은 그 리스트가 손으로 적혀 있으므로 애초에 잡히지 않고, 실제로는 {@code StrategyRegistry} 가 부팅·디스패치에서
 * 잡는다. 채널 이름 오타를 잡던 역할도 {@link SearchChannel} enum 으로 옮겨가 컴파일이 막는다.
 */
class TroubleshootingPlanContributionTest {

    @Test
    @DisplayName("트러블슈팅은 BM25 우위(정확 토큰) + vector 보조 — PRD §2.2 플랜 예시 값")
    void planWeightsFavorKeywordChannel() {
        Map<SearchChannel, Double> weights = new TroubleshootingPlanContribution().channelWeights();

        assertTrue(
                weights.get(SearchChannel.MEMORY_BM25) > weights.get(SearchChannel.MEMORY_VECTOR),
                "에러 시그니처·예외명은 정확 토큰 매칭이 벡터보다 강하다(PRD §04)");
        assertEquals(2.0, weights.get(SearchChannel.MEMORY_BM25));
        assertEquals(1.2, weights.get(SearchChannel.MEMORY_VECTOR));
        assertEquals(2, weights.size(), "구현된 채널만 준다(없는 채널에 가중치를 주면 융합에서 조용히 무시된다)");
    }
}
