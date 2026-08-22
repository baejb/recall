package com.recall.common.exception;

/**
 * 404 — 없는 리소스, 또는 남의 리소스에 by-id 접근.
 *
 * <p><b>존재 은닉</b>: "없다"와 "볼 권한이 없다"를 구분하지 않는다 — 403 으로 답하면 남의 리소스가 존재한다는 사실이 드러난다. 그 판단은 서비스 계층에
 * 있고(소유자 스코프 조회), 여기까지 오면 이미 같은 결론이다.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
