package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.common.TypeStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 유형 커버리지 — 새 유형은 파이프라인 단계별 SPI <b>5종을 모두</b> 구현해야 한다(하나라도 빠지면 {@code
 * StrategyRegistry.get()}이 런타임에 "등록된 전략이 없는 유형"으로 터진다). 그 계약을 부팅 전에 고정한다.
 *
 * <p>P(플래너) 채널 가중치는 결정론이라 값까지 함께 검증한다(PRD 품질기준: "라벨→전략 매핑이 규칙표와 100% 일치, 미정의 0").
 */
class TroubleshootingStrategyCoverageTest {

    /** HybridSearchService가 융합에 쓰는 채널 이름 — 오타가 나면 가중치가 조용히 무시되므로 이름까지 고정한다. */
    private static final String CH_VECTOR = "memory_vector";

    private static final String CH_BM25 = "memory_bm25";

    private static final List<TypeStrategy> ALL_FIVE =
            List.of(
                    new TroubleshootingExtraction(new PromptLoader()),
                    new TroubleshootingSearchRepresentation(),
                    new TroubleshootingPlanContribution(),
                    new TroubleshootingJudge(new PromptLoader()),
                    new TroubleshootingAnswer());

    @Test
    @DisplayName("파이프라인 단계별 SPI 5종(S2·R·P·S4·A)이 모두 TROUBLESHOOTING으로 자가 등록된다")
    void allFiveStrategiesRegisterForTroubleshooting() {
        assertEquals(5, ALL_FIVE.size());
        ALL_FIVE.forEach(
                s ->
                        assertEquals(
                                MemoryType.TROUBLESHOOTING,
                                s.supports(),
                                s.getClass().getSimpleName() + "이 담당 유형을 잘못 밝힌다"));
    }

    @Test
    @DisplayName("P: 트러블슈팅은 BM25 우위(정확 토큰) + vector 보조 — PRD §2.2 플랜 예시 값")
    void planWeightsFavorKeywordChannel() {
        Map<String, Double> weights = new TroubleshootingPlanContribution().channelWeights();

        assertEquals(2, weights.size(), "구현된 채널만 준다(미구현 채널 이름은 넣지 않는다)");
        assertTrue(
                weights.get(CH_BM25) > weights.get(CH_VECTOR),
                "에러 시그니처·예외명은 정확 토큰 매칭이 벡터보다 강하다(PRD §04)");
        assertEquals(2.0, weights.get(CH_BM25));
        assertEquals(1.2, weights.get(CH_VECTOR));
    }
}
