package com.recall.common.exception;

/**
 * 503 — 서버 마스터키(RECALL_SECRET_KEY)가 없어 provider 키를 DB 에 저장/복호화할 수 없다(fail-closed).
 *
 * <p>서버 코드 결함(500)이 아니라 운영자가 마스터키를 주입하면 풀리는 미구성 상태다({@link ErrorCode#SECRET_KEY_UNCONFIGURED} 참고).
 * 사용자가 자기 키를 안 넣은 것({@link AiNotConfiguredException}, 409)과도 다르다 — 이건 서버 측 문제다.
 *
 * <p>메시지에 키 값을 담지 않는다(시큐어코딩).
 */
public class SecretKeyUnavailableException extends ApiException {

    public SecretKeyUnavailableException(String message) {
        super(ErrorCode.SECRET_KEY_UNCONFIGURED, message);
    }
}
