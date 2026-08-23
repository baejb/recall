package com.recall.capture.controller.dto;

/**
 * 원문 저장 응답. 저장 경로는 비동기라 즉시 완료가 아니라 <b>접수</b>를 알린다(HTTP 202).
 *
 * <p>{@code status} 문자열을 팩터리로 고정한다 — 전에는 컨트롤러가 {@code "accepted"} 리터럴을 직접 넘겼다. 응답 어휘를 호출부가 손으로 적으면
 * 오타가 컴파일을 통과해 계약으로 나간다.
 */
public record CaptureResponse(Long captureId, String status) {

    private static final String ACCEPTED = "accepted";

    public static CaptureResponse accepted(Long captureId) {
        return new CaptureResponse(captureId, ACCEPTED);
    }
}
