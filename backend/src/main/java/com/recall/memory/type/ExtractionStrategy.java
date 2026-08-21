package com.recall.memory.type;

import com.recall.common.TypeStrategy;
import com.recall.llm.UserAiContext;
import java.util.Map;

/**
 * S2 구조화 추출 — 유형별 스키마로 마스킹된 원문을 구조화한다(예: 지식=facts/document,
 * 트러블슈팅=symptom/root_cause/attempts/status).
 *
 * <p>Phase 0: 계약만 확정. 반환 타입은 Phase 1에서 유형별 타입드 레코드 + JSON 스키마 강제로 세분화한다.
 */
public interface ExtractionStrategy extends TypeStrategy {

    /**
     * 마스킹된 원문 → 유형별 구조화 필드(승인 전 검토 대기함에 올라갈 후보). LLM 호출은 {@code ctx.requireChat()}로 얻은 클라이언트만 쓴다 —
     * 전역 싱글턴이 아니라 capture 소유자에 바인딩된 클라이언트다(사용자별 provider/키 교차유출 방지). 저장 파이프라인은 이 전략에 도달하기 전에 이미
     * {@code ctx.chatReady()}를 확인하므로 (StorePipeline의 context 게이트), 정상 흐름에선 {@code requireChat()}이
     * 던지지 않는다.
     *
     * @param maskedText M0 마스킹을 이미 거친 원문
     * @param ctx capture 소유자에 바인딩된 AI 컨텍스트
     */
    Map<String, Object> extract(String maskedText, UserAiContext ctx);
}
