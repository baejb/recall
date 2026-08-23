package com.recall.memory.type;

import com.recall.common.type.TypeStrategy;
import com.recall.llm.UserAiContext;

/**
 * S4 유사 판정 + 모순 탐지 — 신규 후보와 유사한 기존 memory를 유형별 기준으로 대조해 재발/보완/충돌/신규를
 * 가린다(트러블슈팅=근본원인·error_signature, 지식=fact 대조).
 */
public interface SimilarityJudgeStrategy extends TypeStrategy {

    /**
     * LLM 호출은 {@code ctx.requireChat()}로 얻은 클라이언트만 쓴다 — 전역 싱글턴이 아니라 capture 소유자에 바인딩된 클라이언트다(사용자별
     * provider/키 교차유출 방지).
     *
     * <p>{@code existing}이 {@code null}이면 유사 후보 자체가 없다는 뜻이다. 전에는 "빈 Map"으로 그 상태를 표현했는데, 빈 카드와 후보
     * 없음을 같은 값으로 나타내 구현마다 {@code null || isEmpty()} 검사를 다시 쓰게 했다.
     *
     * @param proposed 신규 추출 후보
     * @param existing 유사 후보로 걸린 기존 memory의 카드. 후보가 없으면 {@code null}
     * @param ctx capture 소유자에 바인딩된 AI 컨텍스트
     * @return 판정 쪽지(판정·대상·이유). CONFLICT면 자동 반영하지 않고 두 기록 보존 후 검토 요청.
     */
    Judgement judge(MemoryCard proposed, MemoryCard existing, UserAiContext ctx);
}
