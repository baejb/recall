package com.recall.query;

import com.recall.query.dto.AnswerFragment;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 답변을 서버-전송 이벤트(SSE)로 흘려보내는 전송 담당. 컨트롤러의 HTTP 변환과 파이프라인의 조회 로직 사이에서 스트리밍만 맡는다. 근거(memory)가 없으면 지어내지
 * 않고 "기록 없음"을 보낸다(불변 원칙: 근거 없는 생성 금지).
 */
@Component
public class AnswerStreamer {

    private static final Logger log = LoggerFactory.getLogger(AnswerStreamer.class);

    /** SSE 연결 최대 유지 시간(ms). */
    private static final long TIMEOUT_MS = 30_000L;

    private static final String EVENT_NAME = "answer";
    private static final String NO_RECORD_TEXT = "기록 없음";

    private final QueryPipeline pipeline;

    public AnswerStreamer(QueryPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** 질문에 대한 답변 조각을 SSE로 스트리밍하는 emitter를 만든다. */
    public SseEmitter stream(String question) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Thread.ofVirtual().name("sse-answer-").start(() -> emit(emitter, question));
        return emitter;
    }

    private void emit(SseEmitter emitter, String question) {
        try {
            List<AnswerFragment> fragments = pipeline.answer(question);
            if (fragments.isEmpty()) {
                emitter.send(
                        SseEmitter.event().name(EVENT_NAME).data(Map.of("text", NO_RECORD_TEXT)));
            } else {
                for (AnswerFragment fragment : fragments) {
                    emitter.send(SseEmitter.event().name(EVENT_NAME).data(fragment));
                }
            }
            emitter.complete();
        } catch (IOException | RuntimeException e) {
            // 조용한 실패 금지: 끊긴 이유를 로그로 남기고 클라이언트엔 에러로 완료를 알린다.
            log.warn("SSE 답변 스트리밍 실패: {}", e.getMessage(), e);
            emitter.completeWithError(e);
        }
    }
}
