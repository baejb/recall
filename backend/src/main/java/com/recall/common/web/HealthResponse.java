package com.recall.common.web;

/**
 * 실행 확인 응답.
 *
 * <p>전에는 {@code Map.of("status", "ok", "service", "recall")} 였다. Map 을 응답 타입으로 쓰면 (1) 키 이름이 계약인데
 * 컴파일러가 지켜 주지 않고, (2) 응답 스키마가 코드에서 읽히지 않아 프론트가 무엇을 받는지 알려면 구현을 열어야 한다.
 */
public record HealthResponse(String status, String service) {

    /** 살아 있음. 값이 하나뿐인 상태 문자열을 호출부마다 다시 적지 않는다. */
    private static final String UP = "ok";

    private static final String SERVICE = "recall";

    public static HealthResponse up() {
        return new HealthResponse(UP, SERVICE);
    }
}
