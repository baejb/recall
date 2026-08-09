package com.recall.memory.type;

import com.recall.common.TypeStrategy;
import java.util.Map;

/**
 * S2 구조화 추출 — 유형별 스키마로 마스킹된 원문을 구조화한다(예: 지식=facts/document,
 * 트러블슈팅=symptom/root_cause/attempts/status).
 *
 * <p>Phase 0: 계약만 확정. 반환 타입은 Phase 1에서 유형별 타입드 레코드 + JSON 스키마 강제로 세분화한다.
 */
public interface ExtractionStrategy extends TypeStrategy {

    /**
     * 마스킹된 원문 → 유형별 구조화 필드(승인 전 검토 대기함에 올라갈 후보).
     *
     * @param maskedText M0 마스킹을 이미 거친 원문
     */
    Map<String, Object> extract(String maskedText);
}
