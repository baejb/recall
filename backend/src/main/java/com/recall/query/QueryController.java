package com.recall.query;

import com.recall.query.dto.QueryRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 조회 입구 — HTTP 변환만 담당하고, 답변 스트리밍(SSE)은 AnswerStreamer에 맡긴다. */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final AnswerStreamer answerStreamer;

    public QueryController(AnswerStreamer answerStreamer) {
        this.answerStreamer = answerStreamer;
    }

    @PostMapping
    public SseEmitter query(@Valid @RequestBody QueryRequest request) {
        return answerStreamer.stream(request.question());
    }
}
