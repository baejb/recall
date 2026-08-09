package com.recall.capture.dto;

/** 원문 저장 응답. 저장 경로는 비동기라 즉시 완료가 아니라 접수(accepted)를 알린다. */
public record CaptureResponse(Long captureId, String status) {}
