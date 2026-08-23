package com.recall.memory.controller.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * memory 상세 응답.
 *
 * <p>{@code structured}는 승인된 구조화 카드 전체를 유형과 무관하게 그대로 싣는다 — 유형이 늘어날 때마다 이 DTO에 유형별 필드를 더하지 않기 위해서다
 * (트러블슈팅의 symptom·attempts·root_cause 등은 이 맵으로 전달된다). 클라이언트는 {@code type}을 보고 유형별로 렌더한다.
 *
 * <p>{@code keywords}·{@code facts}·{@code document}는 knowledge 카드를 평면화한 <b>레거시 필드</b>다(기존 프론트가 사용
 * 중). 프론트가 {@code structured} 기반 렌더로 옮겨가면 제거한다.
 */
public record MemoryDetailResponse(
        Long id,
        Long captureId,
        String type,
        String title,
        String summary,
        List<String> keywords,
        List<String> facts,
        String document,
        Map<String, Object> structured,
        String status,
        OffsetDateTime createdAt) {}
