package com.recall.common;

/**
 * 404로 변환돼야 하는 도메인 예외(없는 리소스, 또는 남의 리소스에 by-id 접근 — 존재를 노출하지 않는다). 도메인 서비스는 HTTP를 모른다(CLAUDE.md):
 * 서비스는 이 예외만 던지고, HTTP 상태 매핑은 {@link ApiExceptionHandler}가 한 곳에서 한다. common은 리프 모듈이라 다른 기능 모듈을 참조하지
 * 않는다.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
