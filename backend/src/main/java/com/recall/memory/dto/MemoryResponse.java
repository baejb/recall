package com.recall.memory.dto;

import java.time.OffsetDateTime;

/** 승인된 memory 카드 응답. */
public record MemoryResponse(
        Long id,
        Long captureId,
        String type,
        String title,
        String summary,
        String status,
        OffsetDateTime createdAt) {}
