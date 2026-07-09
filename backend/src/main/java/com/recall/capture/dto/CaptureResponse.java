package com.recall.capture.dto;

/**
 * @param captureId persisted capture id
 * @param status    "extracting" while the async job runs; results land in the review queue
 */
public record CaptureResponse(Long captureId, String status) {
}
