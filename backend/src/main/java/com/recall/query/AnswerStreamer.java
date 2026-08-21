package com.recall.query;

import com.recall.common.MemoryType;
import com.recall.memory.Memory;
import com.recall.query.dto.AnswerFragment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 답변을 서버-전송 이벤트(SSE)로 흘려보내는 전송 담당. 컨트롤러의 HTTP 변환과 파이프라인의 조회 로직 사이에서 스트리밍만 맡는다.
 *
 * <p>흐름: 검색(R·W)으로 근거를 찾고 → 근거가 없으면 "기록 없음"(근거 없는 생성 금지) → 있으면 LLM이 근거만으로 답을 합성해 토큰을 흘리고(A) 근거
 * citation을 뒤에 붙인다. LLM 미가용/합성 실패(토큰 전송 전)면 각 근거의 요약으로 격하한다(조용한 실패 금지).
 */
@Component
public class AnswerStreamer {

    private static final Logger log = LoggerFactory.getLogger(AnswerStreamer.class);

    /** SSE 연결 최대 유지 시간(ms). LLM 스트리밍을 고려해 넉넉히 둔다. */
    private static final long TIMEOUT_MS = 60_000L;

    private static final String EVENT_NAME = "answer";
    private static final String NO_RECORD_TEXT = "기록 없음";

    private final QueryPipeline pipeline;

    public AnswerStreamer(QueryPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** 질문에 대한 답변을 SSE로 스트리밍하는 emitter를 만든다(가상 스레드에서 블로킹 LLM 스트림을 소비). */
    public SseEmitter stream(String question) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Thread.ofVirtual().name("sse-answer-").start(() -> emit(emitter, question));
        return emitter;
    }

    void emit(SseEmitter emitter, String question) {
        try {
            MemoryType type = pipeline.classify(question); // C — 질문 유형(지식/트슈)
            List<Memory> candidates = pipeline.retrieve(question, type); // R·W
            if (candidates.isEmpty()) {
                // 근거 없음 → 지어내지 않고 "기록 없음"(LLM 미호출).
                emitter.send(
                        SseEmitter.event().name(EVENT_NAME).data(Map.of("text", NO_RECORD_TEXT)));
                emitter.complete();
                return;
            }

            boolean llm = pipeline.llmReady();
            // RR: LLM 경로에서만 재정렬(실패/미가용 시 W 순서 유지). 이후 답변·citation은 이 evidence 순서를 따른다.
            List<Memory> evidence = llm ? pipeline.rerank(question, candidates) : candidates;

            boolean[] sentText = {false};
            StringBuilder answer = new StringBuilder();
            Consumer<String> textSink =
                    token -> {
                        if (token == null || token.isEmpty()) {
                            return;
                        }
                        sentText[0] = true;
                        answer.append(token);
                        sendFragment(emitter, new AnswerFragment(token, null));
                    };

            boolean composed = false;
            if (llm) {
                try {
                    pipeline.composeStreaming(question, evidence, textSink);
                    composed = true;
                } catch (UncheckedIOException clientGone) {
                    throw clientGone; // 클라이언트 끊김 → 바깥에서 completeWithError
                } catch (RuntimeException llmFailure) {
                    // 합성 실패: 토큰을 이미 보냈으면 부분 답 유지, 아니면 아래에서 격하.
                    log.warn("A(답변합성) 실패: {}", llmFailure.getMessage(), llmFailure);
                }
            }

            if (composed || sentText[0]) {
                // LLM이 "기록 없음"으로 답했으면(근거가 질문에 답 못 함) 근거를 붙이지 않는다 — 모순된 표시 방지.
                if (!isNoRecord(answer.toString())) {
                    // LLM 답(완성/부분) 뒤에 근거 citation을 붙인다(text 없이 memoryId만).
                    for (Memory m : evidence) {
                        sendFragment(emitter, new AnswerFragment("", m.getId()));
                    }
                }
            } else {
                // 격하: 각 근거의 요약(text+memoryId) — 근거에 매인 나열.
                for (AnswerFragment fragment : pipeline.fallbackFragments(evidence)) {
                    sendFragment(emitter, fragment);
                }
            }
            emitter.complete();
        } catch (IOException | RuntimeException e) {
            // 조용한 실패 금지: 끊긴 이유를 로그로 남기고 클라이언트엔 에러로 완료를 알린다.
            log.warn("SSE 답변 스트리밍 실패: {}", e.getMessage(), e);
            emitter.completeWithError(e);
        }
    }

    /** LLM 답이 "기록 없음"인지 — 근거가 질문에 답하지 못한 경우. 끝의 마침표·공백은 무시한다. */
    static boolean isNoRecord(String answer) {
        String normalized = answer.strip().replaceAll("[.\\s]+$", "");
        return normalized.equals(NO_RECORD_TEXT);
    }

    /** 조각 하나를 SSE 이벤트로 보낸다. 전송 실패(클라이언트 끊김)는 UncheckedIOException으로 올려 상위에서 처리한다. */
    private static void sendFragment(SseEmitter emitter, AnswerFragment fragment) {
        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(fragment));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
