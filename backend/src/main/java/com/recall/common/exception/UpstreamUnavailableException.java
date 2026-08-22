package com.recall.common.exception;

/**
 * 502 — 외부 provider(LLM·임베딩)가 응답하지 않거나 5xx 를 냈다.
 *
 * <p><b>{@link ValidationException} 과 갈라야 하는 이유</b> — 둘은 사용자가 할 일이 정반대다. 검증 실패는 "키·모델·base URL 을
 * 고쳐라"이고, 상류 장애는 "고칠 게 없으니 나중에 다시 하라"다. 전에는 설정 저장 전 프로브가 provider 의 <b>모든</b> 실패를 검증 실패로 뭉개서,
 * provider 가 잠깐 죽은 동안 <b>정상 설정을 저장하려는 사용자에게 "입력이 잘못됐다"고 답했다.</b> 게다가 상류 장애가 4xx 로 기록돼 5xx 모니터링에서
 * 사라졌다.
 *
 * <p>메시지에 provider 응답 바디·API 키를 담지 않는다(시큐어코딩) — 상태 코드와 상태 문구까지만.
 */
public class UpstreamUnavailableException extends ApiException {

    public UpstreamUnavailableException(String message) {
        super(ErrorCode.UPSTREAM_UNAVAILABLE, message);
    }
}
