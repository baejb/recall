package com.recall.memory.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** 기억 상태 전이 요청. status=active(복원)|archived(숨김)|incorrect(폐기). */
public record MemoryStatusRequest(@NotBlank String status) {}
