package com.recall.memory.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** memory 상세 응답 — 승인된 구조화 카드(structured)를 펼쳐 준다. */
public record MemoryDetailResponse(
        Long id,
        Long captureId,
        String type,
        String title,
        String summary,
        List<String> keywords,
        List<String> facts,
        String document,
        String status,
        OffsetDateTime createdAt) {}
