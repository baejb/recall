package com.recall.common;

/** 400으로 변환돼야 하는 도메인 예외의 공통 상위 타입(common은 리프 모듈 — 다른 기능 모듈을 참조하지 않는다). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
