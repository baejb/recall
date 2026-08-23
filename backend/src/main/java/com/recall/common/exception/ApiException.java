package com.recall.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인·애플리케이션 예외의 공통 부모. {@link ErrorCode}를 들고 있고, {@link GlobalExceptionHandler}가 그 코드로 공통 응답 형식을
 * 만든다 — 모듈마다 try-catch 로 HTTP 를 만들지 않는다.
 *
 * <p>HTTP 상태는 예외가 정하지 않고 {@code ErrorCode}에서 나온다 — 코드와 상태가 두 곳에 있으면 어긋날 수 있다.
 *
 * <p>도메인 서비스는 HTTP 를 모른다: 서비스는 이 예외 계층만 던지고 상태 매핑은 핸들러 한 곳에서 한다.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;

    /** 필드 단위 오류일 때만 채우는 필드명(없으면 null). 값이 아니라 <b>이름</b>이라 노출 위험이 없다. */
    private final String field;

    protected ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    protected ApiException(ErrorCode code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return code.status();
    }

    public String field() {
        return field;
    }
}
