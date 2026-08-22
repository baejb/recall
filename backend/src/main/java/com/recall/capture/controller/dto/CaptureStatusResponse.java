package com.recall.capture.controller.dto;

import java.time.OffsetDateTime;

/** 캡처 처리 상태 응답(정리 중/실패 노출용). 원문(raw_text)은 포함하지 않는다 — 상태 노출에 불필요하고 마스킹 원문을 굳이 다시 내보내지 않기 위함. */
public record CaptureStatusResponse(
        Long id, String status, String sourceType, String failedStage, OffsetDateTime createdAt) {}
