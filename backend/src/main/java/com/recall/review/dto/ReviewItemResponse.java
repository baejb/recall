package com.recall.review.dto;

import java.time.OffsetDateTime;

/** 검토 대기함 항목 응답. */
public record ReviewItemResponse(
        Long id,
        Long captureId,
        String judgement,
        String status,
        String proposed,
        OffsetDateTime createdAt) {}
