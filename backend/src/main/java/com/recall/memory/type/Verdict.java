package com.recall.memory.type;

/**
 * S4 유사 판정 결과. 검토 대기함(review_queue.judgement)과 같은 정의를 공유한다. 충돌(CONFLICT)은 자동 덮어쓰기 없이 두 기록을 보존한 채
 * 검토로 넘긴다(불변 원칙).
 */
public enum Verdict {
    NEW,
    RECURRENCE,
    SUPPLEMENT,
    CONFLICT
}
