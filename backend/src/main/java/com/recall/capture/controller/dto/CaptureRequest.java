package com.recall.capture.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** 원문 저장 요청. sourceType 생략 시 chat 취급. */
public record CaptureRequest(String sourceType, @NotBlank String rawText) {}
