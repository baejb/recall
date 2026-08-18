package com.recall.common;

/**
 * 사용자가 chat 또는 embedding 모델을 아직 설정하지 않은 상태에서 그 기능을 요구하는 호출이 들어왔을 때 던진다({@code
 * UserAiContext#requireChat()}/{@code #requireEmbedding()}). {@link ApiExceptionHandler}가 409(설정 선행
 * 필요)로 변환한다. common은 리프 모듈이라 다른 기능 모듈을 참조하지 않는다.
 */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException(String message) {
        super(message);
    }
}
