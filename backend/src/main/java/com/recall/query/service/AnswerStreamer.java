package com.recall.query.service;

import com.recall.common.type.MemoryType;
import com.recall.llm.UserAiContext;
import com.recall.memory.service.entity.Memory;
import com.recall.query.controller.dto.AnswerFragment;
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
 * citation을 뒤에 붙인다. 합성 실패(토큰 전송 전)면 각 근거의 요약으로 격하한다(조용한 실패 금지).
 *
 * <p>사용자별 AI 컨텍스트({@link UserAiContext})는 요청 스레드(컨트롤러)에서 이미 만들어져 넘어온다 — chat 미설정(차단, 409)은 {@code
 * QueryController}가 스트림을 시작하기 전에 걸러내므로, 여기 도달했다면 chat은 설정된 상태다. 설정 완료 후의 외부 LLM 호출 실패는 이 클래스가 잡아 기존
 * 격하(요약)로 응답한다 — 미설정(차단)과 외부 장애(격하)를 섞지 않는다.
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
    public SseEmitter stream(String question, UserAiContext ctx) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        // ctx(소유자·chat/embedding 클라이언트)는 요청 스레드에서 이미 해석해 넘겨받는다 — SSE 가상 스레드에는
        // SecurityContext(thread-local)가 전파되지 않으므로 여기서 다시 풀지 않는다(교차유출 방지). 클로저로
        // 캡처해 요청 사용자가 바뀌어도 이 스트림은 캡처 시점의 컨텍스트로만 동작한다.
        Thread.ofVirtual().name("sse-answer-").start(() -> emit(emitter, question, ctx));
        return emitter;
    }

    void emit(SseEmitter emitter, String question, UserAiContext ctx) {
        try {
            MemoryType type = pipeline.classify(question, ctx); // C — 질문 유형(지식/트슈)
            List<Memory> candidates = pipeline.retrieve(question, type, ctx); // R·W(ctx.userId 스코프)
            if (candidates.isEmpty()) {
                // 검색은 분류된 유형으로만 스코프된다(searchByVector/searchByKeyword 둘 다 `type = ?` —
                // boost 가 아니라 배타적 필터). 그래서 "기록 없음"에는 두 원인이 섞인다: 정말 기록이 없거나,
                // C 가 유형을 잘못 찍어 다른 파티션을 뒤졌거나. 분류가 성공하면 로그가 하나도 남지 않아 사후에
                // 둘을 구분할 수 없었으므로, 빈 결과에는 판단된 유형을 함께 남긴다(조용한 실패 금지).
                // 질문 원문은 남기지 않는다 — 로그는 마스킹 경로를 거치지 않는다.
                log.info("기록 없음 응답: user={} type={}", ctx.userId(), type);
                emitter.send(
                        SseEmitter.event().name(EVENT_NAME).data(Map.of("text", NO_RECORD_TEXT)));
                emitter.complete();
                return;
            }

            // RR: 후보 1개 이하면 파이프라인 내부에서 chat 호출 없이 그대로, 그 외엔 재정렬(호출 실패 시 W 순서
            // 유지 — 격하). chat 미설정 차단은 이미 조회 입구(QueryController)를 통과했으므로 여기선 발생하지 않는다.
            List<Memory> evidence = pipeline.rerank(question, candidates, ctx);

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
            try {
                pipeline.composeStreaming(question, evidence, textSink, ctx);
                composed = true;
            } catch (UncheckedIOException clientGone) {
                throw clientGone; // 클라이언트 끊김 → 바깥에서 completeWithError
            } catch (RuntimeException llmFailure) {
                // 설정은 됐으나(chatReady) 외부 호출이 실패 — 미설정 차단과는 다른 상황(격하): 토큰을 이미
                // 보냈으면 부분 답 유지, 아니면 아래에서 요약 격하.
                log.warn("A(답변합성) 실패: {}", llmFailure.getMessage(), llmFailure);
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
