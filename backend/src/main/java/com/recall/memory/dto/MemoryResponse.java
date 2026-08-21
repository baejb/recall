package com.recall.memory.dto;

import java.time.OffsetDateTime;

/**
 * 승인된 memory 카드 응답(목록 행).
 *
 * <p>상태가 둘이라 이름을 구분한다: {@code status}는 기억의 수명 상태(active·archived·incorrect, "삭제 대신 상태 보존"), {@code
 * cardStatus}는 <b>카드 내용의 상태</b>다(트러블슈팅의 해결 여부).
 *
 * @param cardStatus 카드가 스스로 정의한 상태 — 트러블슈팅은 {@code RESOLVED|PARTIAL|UNRESOLVED}, 그 필드를 두지 않는
 *     유형(지식)은 null. 목록에서 해결 여부 배지를 실제 값으로 그리기 위해 싣는다(모르는 값을 "미해결"로 단정하지 않도록)
 */
public record MemoryResponse(
        Long id,
        Long captureId,
        String type,
        String title,
        String summary,
        String cardStatus,
        String status,
        OffsetDateTime createdAt) {}
