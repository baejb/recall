package com.recall.memory.type;

import com.recall.common.TypeStrategy;
import com.recall.llm.UserAiContext;
import java.util.Map;

/**
 * S4 유사 판정 + 모순 탐지 — 신규 후보와 유사한 기존 memory를 유형별 기준으로 대조해 재발/보완/충돌/신규를
 * 가린다(트러블슈팅=근본원인·error_signature, 지식=fact 대조).
 *
 * <p>Phase 0: 계약만 확정. 입력 타입은 Phase 1에서 유형별 레코드로 세분화한다.
 */
public interface SimilarityJudgeStrategy extends TypeStrategy {

    /**
     * LLM 호출은 {@code ctx.requireChat()}로 얻은 클라이언트만 쓴다 — 전역 싱글턴이 아니라 capture 소유자에 바인딩된 클라이언트다(사용자별
     * provider/키 교차유출 방지).
     *
     * @param proposed 신규 추출 후보
     * @param existing 유사 후보로 걸린 기존 memory의 구조화 필드
     * @param ctx capture 소유자에 바인딩된 AI 컨텍스트
     * @return 판정 쪽지(판정·대상·이유). CONFLICT면 자동 반영하지 않고 두 기록 보존 후 검토 요청.
     */
    Judgement judge(Map<String, Object> proposed, Map<String, Object> existing, UserAiContext ctx);
}
