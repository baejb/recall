package com.recall.common.exception;

/**
 * 400 — 요청 값의 형식·범위 위반. 어느 필드인지 함께 싣는다.
 *
 * <p>이전 {@code ValidationException}을 대체한다: 이름이 HTTP 상태("badRequest")를 말하고 있어서 도메인 서비스가 HTTP 를 아는
 * 것처럼 읽혔다. 상태는 {@link ErrorCode}가 정하고, 예외 이름은 "무슨 일이 있었나"만 말한다.
 */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }

    public ValidationException(String message, String field) {
        super(ErrorCode.VALIDATION_ERROR, message, field);
    }
}
