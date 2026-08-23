package com.recall.common.exception;

/**
 * 409 — 사용자가 chat 또는 embedding 모델을 아직 설정하지 않은 상태에서 그 기능을 요구하는 호출({@code
 * UserAiContext#requireChat()}/{@code #requireEmbedding()}).
 *
 * <p><b>차단(이 예외)과 격하는 다른 상황이다</b>: 설정이 없어 애초에 할 수 없는 것은 요청을 막고(409), 설정은 됐는데 외부 API 호출이 실패한 것은 격하해
 * 응답한다(BM25 만으로 검색, 요약 fallback 등). 이 둘을 하나로 섞으면 사용자가 "설정하러 가야 하는지" 판단할 수 없다.
 *
 * <p>메시지에 API 키 값을 담지 않는다(시큐어코딩 — 자격증명은 로그·응답에 남기지 않는다).
 */
public class AiNotConfiguredException extends ApiException {

    public AiNotConfiguredException(String message) {
        super(ErrorCode.AI_NOT_CONFIGURED, message);
    }
}
