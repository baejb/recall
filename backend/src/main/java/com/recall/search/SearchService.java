package com.recall.search;

import com.recall.intent.QueryIntent;
import com.recall.search.dto.Candidate;
import com.recall.search.dto.QueryPlan;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Query path (synchronous, SSE): classify → plan → hybrid retrieve → compose.
 * Only two LLM calls total (intent + answer), so it stays synchronous.
 */
@Service
public class SearchService {

    private final QueryClassifier classifier;
    private final SearchPlanner planner;
    private final HybridRetriever retriever;
    private final AnswerComposer composer;

    public SearchService(QueryClassifier classifier,
                         SearchPlanner planner,
                         HybridRetriever retriever,
                         AnswerComposer composer) {
        this.classifier = classifier;
        this.planner = planner;
        this.retriever = retriever;
        this.composer = composer;
    }

    public SseEmitter answer(String query) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            QueryIntent intent = classifier.classify(query);
            QueryPlan plan = planner.plan(query, intent);
            List<Candidate> candidates = retriever.retrieve(query, plan);
            composer.compose(query, plan, candidates, emitter);
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
