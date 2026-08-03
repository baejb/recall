package com.recall.memory.type;

/**
 * S4 판정 결과. "무엇이냐(verdict)"뿐 아니라 "어떤 기존 기록과(targetMemoryId)·왜(rationale)"까지 담아 충돌·재발을 사용자 검토로 제대로
 * 넘길 수 있게 한다. PRD S4 출력 {verdict, target_memory_id?, rationale}과 일치.
 *
 * @param verdict 판정(신규/재발/보완/충돌)
 * @param targetMemoryId 관련된 기존 memory id. 신규(NEW)면 null.
 * @param rationale 그렇게 판정한 근거(검토 화면에 노출).
 */
public record Judgement(Verdict verdict, Long targetMemoryId, String rationale) {}
