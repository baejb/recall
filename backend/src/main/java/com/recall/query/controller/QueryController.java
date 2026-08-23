package com.recall.query.controller;

import com.recall.common.config.CurrentUserProvider;
import com.recall.llm.AiContextFactory;
import com.recall.llm.UserAiContext;
import com.recall.query.controller.dto.QueryRequest;
import com.recall.query.service.AnswerStreamer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 조회 입구 — HTTP 변환만 담당하고, 답변 스트리밍(SSE)은 AnswerStreamer에 맡긴다.
 *
 * <p><b>공통 응답 형식({@code ApiResponse})의 유일한 예외</b>다. 이 엔드포인트의 응답은 하나의 JSON 본문이 아니라 토큰 조각의 흐름이라, 조각마다
 * 공통 형식을 씌우면 프레임 크기가 배로 늘고 스트림 소비 코드가 매 조각에서 {@code success} 를 다시 본다. 실패는 공통 형식이 아니라 SSE 규약으로 알린다:
 * 스트림 시작 <b>전</b> 실패는 아래 {@code requireChat()} 게이트가 예외로 던져 전역 핸들러가 평소의 에러 응답을 내고, 시작 <b>후</b> 실패는
 * {@code AnswerStreamer} 가 격하 조각 또는 {@code completeWithError} 로 알린다.
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final AnswerStreamer answerStreamer;
    private final CurrentUserProvider currentUser;
    private final AiContextFactory contextFactory;

    public QueryController(
            AnswerStreamer answerStreamer,
            CurrentUserProvider currentUser,
            AiContextFactory contextFactory) {
        this.answerStreamer = answerStreamer;
        this.currentUser = currentUser;
        this.contextFactory = contextFactory;
    }

    @PostMapping
    public SseEmitter query(@Valid @RequestBody QueryRequest request) {
        // 소유자는 요청 스레드에서 해석해 SSE로 넘긴다(가상 스레드엔 SecurityContext 미전파).
        UserAiContext ctx = contextFactory.forUser(currentUser.currentUserId());
        // chat 미설정이면 여기서 즉시 차단(409) — SSE는 200을 먼저 돌려주므로 스트림을 시작하면 더는 상태
        // 코드를 바꿀 수 없다. 설정 완료 후의 외부 LLM 실패는 이 게이트를 통과한 뒤 AnswerStreamer가
        // 기존 격하 정책으로 처리한다(미설정 차단과 외부 장애 격하를 섞지 않는다).
        ctx.requireChat();
        return answerStreamer.stream(request.question(), ctx);
    }
}
