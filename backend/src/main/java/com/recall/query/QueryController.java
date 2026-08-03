package com.recall.query;

import com.recall.query.dto.AnswerFragment;
import com.recall.query.dto.QueryRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 조회 입구 — 답변을 서버-전송 이벤트(SSE)로 스트리밍한다. */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryPipeline pipeline;

    public QueryController(QueryPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping
    public SseEmitter query(@Valid @RequestBody QueryRequest request) {
        SseEmitter emitter = new SseEmitter(30_000L);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                List<AnswerFragment> fragments =
                                        pipeline.answer(request.question());
                                if (fragments.isEmpty()) {
                                    // 근거 없는 생성 금지 — 지어내지 않고 "기록 없음".
                                    emitter.send(
                                            SseEmitter.event()
                                                    .name("answer")
                                                    .data(Map.of("text", "기록 없음")));
                                } else {
                                    for (AnswerFragment fragment : fragments) {
                                        emitter.send(
                                                SseEmitter.event().name("answer").data(fragment));
                                    }
                                }
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        });
        return emitter;
    }
}
