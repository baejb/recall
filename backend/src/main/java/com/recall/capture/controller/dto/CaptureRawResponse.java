package com.recall.capture.controller.dto;

import java.time.OffsetDateTime;

/**
 * 원본 캡처 조회 응답 — memory 상세의 근거(evidence) 확인용. rawText 는 캡처 시점에 이미 마스킹된 원문이라(CaptureService.capture
 * 참고) 그대로 노출해도 안전하다.
 */
public record CaptureRawResponse(
        Long id, String sourceType, String rawText, OffsetDateTime createdAt) {}
