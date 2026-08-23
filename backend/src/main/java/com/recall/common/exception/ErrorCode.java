package com.recall.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드와 HTTP 상태의 <b>유일한 정의</b>.
 *
 * <p><b>왜 열거인가</b> — 전에는 상태 매핑이 {@code ApiExceptionHandler}의 핸들러 메서드마다 흩어져 있었고 코드 자체가 없었다(응답에
 * {@code detail} 문장만 나갔다). 그러면 (1) 클라이언트가 분기할 수 있는 안정된 식별자가 없어 화면이 한국어 메시지 문자열을 비교하게 되고, (2) "이 상황이
 * 몇 번으로 나가는가"가 코드 여러 곳에 흩어져 어긋날 수 있다. 코드와 상태를 한 자리에 묶으면 계약을 한 번에 읽을 수 있고, 새 코드를 만들려면 여기 한 줄을 추가하며
 * 기존 표와 맞는지 보게 된다.
 *
 * <p>상태를 예외 타입이 아니라 이 열거가 들고 있는 이유: 예외 타입과 코드가 각자 상태를 정하면 둘이 어긋날 수 있다. 예외는 "무슨 일이 있었나"만 말하고 "몇 번으로
 * 나가나"는 코드가 정한다.
 */
public enum ErrorCode {

    /** 400 — 요청 값의 형식·범위 위반(@Valid 실패, 허용 밖 status/type, 깨진 커서). */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    /** 404 — 없는 리소스, 또는 남의 리소스에 by-id 접근. 부재와 권한 없음을 구분하지 않는다(존재 은닉). */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /**
     * 405 — 라우트는 있으나 그 메서드를 지원하지 않음.
     *
     * <p>전에는 이 상황에 {@link #VALIDATION_ERROR}(선언 상태 400)를 실으면서 HTTP 405 로 응답해, <b>코드와 상태가 어긋난
     * 응답</b>이 나갔다 — 이 열거가 상태를 소유한다는 계약을 핸들러가 손으로 깨고 있었다. 상황마다 코드를 만들면 계약이 저절로 지켜진다.
     */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

    /**
     * 415 — 지원하지 않는 요청 Content-Type.
     *
     * <p>이 코드가 없던 동안 JSON 엔드포인트에 {@code text/plain} 을 보내면 catch-all 이 붙잡아 <b>500</b>으로 나갔다. 구 핸들러가
     * {@code ResponseEntityExceptionHandler} 를 상속해 스프링 표준 예외를 부모가 처리했는데, 상속을 떼면서 그 커버리지가 함께 사라진
     * 것이다.
     */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    /** 406 — 클라이언트가 Accept 로 요구한 응답 형식을 제공할 수 없음. 415 와 같은 계열의 구멍이었다. */
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),

    /** 409 — 이미 처리된 항목을 다시 처리하려는 등 현재 상태와 모순되는 요청. */
    CONFLICT(HttpStatus.CONFLICT),

    /**
     * 409 — chat/embedding 모델 미설정 상태에서 그 기능을 요구함(설정 선행 필요).
     *
     * <p>{@link #CONFLICT}와 코드를 나눈 이유: 프론트가 "설정 화면으로 보내야 하는 상황"과 "상태가 모순된 상황"을 구분해야 한다. 상태가 같아도
     * 사용자가 할 일이 다르면 다른 코드다.
     */
    AI_NOT_CONFIGURED(HttpStatus.CONFLICT),

    /** 501 — 아직 구현되지 않은 단계. 서버 고장(500)과 구분되어야 한다. */
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED),

    /**
     * 502 — 외부 LLM/임베딩 provider 가 응답하지 않거나 5xx 를 냈다. <b>사용자 입력 잘못이 아니다.</b>
     *
     * <p>전에는 이런 상황도 400 {@code VALIDATION_ERROR} 로 나갔다(설정 저장 전 프로브가 provider 의 모든 실패를 하나로 뭉갰다). 두
     * 가지가 따라왔다: 사용자에게 "키·모델을 확인하라"고 <b>틀린 안내</b>를 하고(정작 고칠 게 없다), 상류 장애가 4xx 로 기록돼 <b>5xx 모니터링에서
     * 사라진다</b>. 내가 고칠 수 있는 것과 상류가 고칠 것은 다른 코드여야 한다.
     */
    UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY),

    /**
     * 503 — 비동기 응답(SSE)이 허용 시간을 넘겼다.
     *
     * <p>근거가 많거나 provider 가 느려 답변 스트리밍이 타임아웃을 넘기는 것은 <b>예측 가능한 운영 상황</b>이고 재시도가 가능하다. 서버 결함(500)과
     * 섞으면 실제 결함이 이 소음에 묻힌다. 구 핸들러의 부모 ({@code ResponseEntityExceptionHandler})가 이 표준 예외를 503 으로
     * 처리하던 것을, 상속을 떼면서 잃었다.
     */
    ASYNC_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),

    /**
     * 401 — 로그인이 필요하다(세션 없음·만료).
     *
     * <p>{@link #NOT_FOUND} 와 구분되는 지점: 404 는 "이 리소스는 당신 것이 아니다"를 존재 은닉으로 답하는 것이고, 401 은 "누구인지
     * 모른다"다. 화면의 반응도 달라야 한다 — 401 은 로그인으로 보내고, 404 는 목록으로 되돌린다.
     *
     * <p>API 경로에서는 <b>리다이렉트가 아니라 이 코드</b>로 답한다. SPA 의 fetch 가 로그인 페이지 HTML 을 200 으로 받으면 "성공했지만 JSON
     * 이 아닌 응답"이 되어 원인을 알 수 없는 파싱 실패로 나타난다.
     */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    /**
     * 403 — 인증은 됐지만 이 인스턴스가 허용한 계정이 아니다(허용목록 밖).
     *
     * <p>401 과 나누는 이유: 401 은 "로그인하면 된다"이고 403 은 "로그인해도 안 된다"다. 하나로 묶으면 화면이 허용되지 않은 계정을 로그인 루프에
     * 빠뜨린다.
     */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** 500 — 분류되지 않은 예외. 내부 정보는 응답에 싣지 않는다(로그로만). */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
