package com.recall.common.exception;

/**
 * 409 — 현재 상태와 모순되는 요청(이미 처리된 검토 항목의 재처리 등).
 *
 * <p>이런 상황이 전에는 {@code IllegalStateException}으로 던져졌고, 전역 핸들러에 그 타입 핸들러가 없어 catch-all 이 붙잡아
 * <b>500</b>으로 나갔다 — 호출자가 고칠 수 있는 상황이 서버 장애로 보고되면 원인 추적이 엉뚱한 곳으로 간다.
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
