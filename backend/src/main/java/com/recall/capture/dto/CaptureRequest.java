package com.recall.capture.dto;

import jakarta.validation.constraints.NotBlank;

public record CaptureRequest(
        @NotBlank String text,
        String sourceType
) {
    public String sourceTypeOrDefault() {
        return sourceType == null || sourceType.isBlank() ? "chat" : sourceType;
    }
}
