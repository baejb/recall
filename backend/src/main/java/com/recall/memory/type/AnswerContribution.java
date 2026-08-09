package com.recall.memory.type;

import com.recall.common.TypeStrategy;
import java.util.Map;

/**
 * A(Answer Composer)의 유형별 기여 — 답변 형식은 유형 × query_intent 로 변하므로, 공유 Composer가 intent(회상/비교 등)를 처리하고
 * 유형 전략은 <b>근거·필드 조각만</b> 기여한다(순수 per-type 포매터로 묶지 않는다 — architecture.md 가드레일 2).
 *
 * <p>근거 없는 문장은 만들지 않는다. 후보가 없으면 상위(Composer)가 "기록 없음"을 반환한다.
 */
public interface AnswerContribution extends TypeStrategy {

    /** memory 구조화 필드 → 답변에 넣을 근거·필드 조각(citation 대상 포함). */
    String render(Map<String, Object> memory);
}
