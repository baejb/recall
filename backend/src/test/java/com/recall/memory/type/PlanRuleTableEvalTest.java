package com.recall.memory.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.common.type.MemoryType;
import com.recall.memory.type.knowledge.KnowledgePlanContribution;
import com.recall.memory.type.troubleshooting.TroubleshootingPlanContribution;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P(Search Planner) Eval — PRD §05 의 합격 기준은 <b>"라벨→전략 매핑이 규칙표와 100% 일치, 미정의 0"</b> 이다. 결정론 단계라 라벨셋이
 * 아니라 <b>규칙표 자체</b>를 검증한다(같은 입력=같은 출력이므로 표를 고정하면 그게 Eval 이다).
 *
 * <p>검증하는 세 가지:
 *
 * <ol>
 *   <li><b>커버리지</b> — 등록된 유형이 모두 가중치를 준다. 빠지면 {@code StrategyRegistry.get}이 런타임에 터진다.
 *   <li><b>미정의 0</b> — 준 채널이 모두 검색기가 실제로 순위를 내는 채널이다. 구현되지 않은 채널에 가중치를 주면 융합에서 무시돼 "설정했는데 안 먹는" 상태가
 *       된다.
 *   <li><b>유형별 방향</b> — 지식은 vector 우위, 트러블슈팅은 BM25 우위. 이건 PRD §04 의 근거("에러 코드·예외명은 정확 토큰 매칭이 벡터보다
 *       강하다")가 값에 반영돼 있는지 보는 것이다. 값 자체는 라벨셋 fit 대상이지만 <b>우열 관계가 뒤집히면</b> 그건 튜닝이 아니라 설계 위반이다.
 * </ol>
 *
 * <p>새 유형을 붙이면 이 테스트가 먼저 실패해야 한다 — 그래서 유형 목록을 여기 명시한다(자동 수집하면 유형을 빠뜨린 채 초록이 된다).
 */
class PlanRuleTableEvalTest {

    /** 검색기가 실제로 순위 리스트를 내는 채널 = 가중치를 줘도 되는 채널의 전체 집합. */
    private static final List<SearchChannel> IMPLEMENTED_CHANNELS =
            List.of(SearchChannel.MEMORY_VECTOR, SearchChannel.MEMORY_BM25);

    /** 규칙표. 유형이 늘면 여기에 한 줄 추가해야 커버리지 검사가 통과한다. */
    private static final Map<MemoryType, PlanContribution> RULE_TABLE =
            new EnumMap<>(
                    Map.of(
                            MemoryType.KNOWLEDGE, new KnowledgePlanContribution(),
                            MemoryType.TROUBLESHOOTING, new TroubleshootingPlanContribution()));

    @Test
    @DisplayName("커버리지 — 규칙표의 전략이 자기 유형을 정확히 담당한다")
    void everyStrategyOwnsItsType() {
        RULE_TABLE.forEach(
                (type, plan) ->
                        assertEquals(
                                type,
                                plan.supports(),
                                () -> plan.getClass().getSimpleName() + " 가 담당 유형을 잘못 밝힌다"));
    }

    @Test
    @DisplayName("미정의 0 — 구현되지 않은 채널에는 가중치를 주지 않는다")
    void noWeightsForUnimplementedChannels() {
        RULE_TABLE.forEach(
                (type, plan) ->
                        plan.channelWeights()
                                .keySet()
                                .forEach(
                                        channel ->
                                                assertTrue(
                                                        IMPLEMENTED_CHANNELS.contains(channel),
                                                        () ->
                                                                type
                                                                        + " 가 검색기에 없는 채널에 가중치를 줬다: "
                                                                        + channel)));
    }

    @Test
    @DisplayName("모든 유형이 최소 한 채널에 양수 가중치를 준다(빈 표 = 검색 불가)")
    void everyTypeSearchesSomething() {
        RULE_TABLE.forEach(
                (type, plan) -> {
                    Map<SearchChannel, Double> weights = plan.channelWeights();
                    assertFalse(weights.isEmpty(), () -> type + " 의 채널 가중치가 비었다");
                    assertTrue(
                            weights.values().stream().anyMatch(w -> w > 0),
                            () -> type + " 의 가중치가 전부 0 이하다");
                });
    }

    @Test
    @DisplayName("유형별 방향 — 지식은 vector 우위, 트러블슈팅은 BM25 우위(PRD §04)")
    void weightDirectionMatchesPrd() {
        Map<SearchChannel, Double> knowledge =
                RULE_TABLE.get(MemoryType.KNOWLEDGE).channelWeights();
        Map<SearchChannel, Double> troubleshooting =
                RULE_TABLE.get(MemoryType.TROUBLESHOOTING).channelWeights();

        assertTrue(
                knowledge.get(SearchChannel.MEMORY_VECTOR)
                        > knowledge.get(SearchChannel.MEMORY_BM25),
                "지식은 의미 유사(vector)가 주 채널이어야 한다");
        assertTrue(
                troubleshooting.get(SearchChannel.MEMORY_BM25)
                        > troubleshooting.get(SearchChannel.MEMORY_VECTOR),
                "트러블슈팅은 정확 토큰(BM25)이 주 채널이어야 한다 — 에러 코드·예외명 때문");
    }
}
