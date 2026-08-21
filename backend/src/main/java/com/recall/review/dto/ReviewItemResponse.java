package com.recall.review.dto;

import java.time.OffsetDateTime;

/**
 * 검토 대기함 항목 응답.
 *
 * @param targetMemoryId 재발/충돌 판정의 대상 기존 memory(신규면 null)
 * @param judgeReason 판정 근거
 * @param memoryType 승인 시 만들 memory 유형
 */
public record ReviewItemResponse(
        Long id,
        Long captureId,
        String judgement,
        Long targetMemoryId,
        String judgeReason,
        String memoryType,
        String status,
        String proposed,
        OffsetDateTime createdAt) {}
