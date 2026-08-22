package com.recall.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 REST 응답 본문의 공통 형태 — 성공은 {@code {success:true, data}}, 실패는 {@code {success:false, error}}.
 *
 * <p><b>왜 봉투를 하나로 두나</b> — 전에는 성공이 원본 DTO 그대로였고 실패만 {@code ProblemDetail}(RFC 7807)이었다. 형태가 비대칭이면
 * 호출자가 <b>본문을 파싱하기도 전에 상태 코드로 먼저 갈라야</b> 하고, 화면·어댑터·테스트가 각자 그 분기를 다시 만든다. 형태가 하나면 "먼저 success 를 보고
 * data 나 error 를 읽는다"가 유일한 규칙이 된다.
 *
 * <p>목록·페이지 응답은 {@code data} 안에 기존 페이지 DTO 가 그대로 들어간다 — 봉투가 두 겹인 것은 의미가 다르기 때문이다: 바깥은 성공/실패, 안쪽은
 * 페이지 메타.
 *
 * <p>null 필드는 직렬화에서 빠진다 — 성공 응답에 빈 {@code error} 가, 실패 응답에 빈 {@code data} 가 붙어 있으면 읽는 쪽이 그 존재를 판정
 * 조건으로 착각한다.
 *
 * <p><b>예외</b>: {@code POST /api/queries} 는 SSE 스트림이라 이 봉투를 쓰지 않는다 — 응답이 하나의 JSON 본문이 아니라 토큰 조각의
 * 흐름이고, 조각마다 봉투를 씌우면 프레임 크기가 배로 늘고 스트림 소비 코드가 매 조각에서 success 를 다시 본다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    /** 성공 + 본문. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 성공했지만 돌려줄 값이 없는 경우(반려·상태 전이 등) — {@code data} 는 직렬화에서 빠진다. */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> fail(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}
