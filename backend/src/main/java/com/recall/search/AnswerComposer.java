package com.recall.search;

import com.recall.search.dto.Candidate;
import com.recall.search.dto.QueryPlan;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Reconstructs an answer strictly within the retrieved memory/capture scope and streams
 * it over SSE. Non-negotiable: no answer without evidence — when nothing is retrieved we
 * say "no record" rather than inventing one.
 */
@Service
public class AnswerComposer {

    public void compose(String query, QueryPlan plan, List<Candidate> candidates, SseEmitter emitter)
            throws IOException {
        if (candidates.isEmpty()) {
            emitter.send(SseEmitter.event()
                    .name("answer")
                    .data("관련 기록 없음 — 이 질문에 대한 근거를 찾지 못했습니다. (근거 없는 답변 금지)"));
            emitter.send(SseEmitter.event().name("done").data("{}"));
            return;
        }
        // TODO: strong-model reconstruction ("당시 vs 지금" / latest document) with evidence links.
        emitter.send(SseEmitter.event()
                .name("answer")
                .data("TODO: evidence-based answer composition for " + candidates.size() + " candidate(s)"));
        emitter.send(SseEmitter.event().name("done").data("{}"));
    }
}
