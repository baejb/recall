package com.recall.common.type;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 유형별 전략 목록을 {@code MemoryType -> 전략} 조회로 바꿔주는 레지스트리.
 *
 * <p>오케스트레이터는 Spring이 주입한 {@code List<T>}(각 전략이 {@link TypeStrategy#supports()}로 자가 등록)를 이 레지스트리로
 * 감싸 유형별로 디스패치한다. 공유 코드에 {@code switch(MemoryType)}를 두지 않기 위한 장치다.
 *
 * @param <T> 전략 SPI 타입
 */
public final class StrategyRegistry<T extends TypeStrategy> {

    private final Map<MemoryType, T> byType;

    public StrategyRegistry(List<T> strategies) {
        Map<MemoryType, T> map = new EnumMap<>(MemoryType.class);
        for (T strategy : strategies) {
            T previous = map.put(strategy.supports(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "한 유형에 전략이 둘 이상 등록됨: "
                                + strategy.supports()
                                + " → ["
                                + previous.getClass().getSimpleName()
                                + ", "
                                + strategy.getClass().getSimpleName()
                                + "]");
            }
        }
        this.byType = map;
    }

    /** 유형별 전략을 반환한다. 없으면 조용히 넘기지 않고 예외로 드러낸다(조용한 실패 금지). */
    public T get(MemoryType type) {
        T strategy = byType.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("등록된 전략이 없는 유형: " + type);
        }
        return strategy;
    }

    /** 현재 전략이 등록된 유형 집합(부팅 시 커버리지 로깅·검증용). */
    public Set<MemoryType> registered() {
        return Collections.unmodifiableSet(byType.keySet());
    }
}
