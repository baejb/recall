package com.recall.common.exception;

/**
 * 401 — 로그인하지 않은(또는 세션이 만료된) 요청.
 *
 * <p>주로 소유자 해석 지점에서 던진다: {@code CurrentUserProvider} 가 principal 을 못 찾았을 때 기본값으로 넘어가면 인증 없이 남의 데이터에
 * 닿는 경로가 열리므로, 그 자리는 <b>반드시 실패해야</b> 한다(🔴 교차유출).
 */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException(String message) {
        super(ErrorCode.UNAUTHENTICATED, message);
    }
}
