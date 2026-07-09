package com.recall.intent;

import com.recall.llm.LlmClient;
import org.springframework.stereotype.Service;

/**
 * Cheap-model classifier. On the store path it decides store vs query; on the query path
 * it resolves the sub-intent (FIND/COMPARE/...).
 */
@Service
public class IntentRouter {

    private final LlmClient llm;

    public IntentRouter(LlmClient llm) {
        this.llm = llm;
    }

    public IntentType classify(String text) {
        // TODO: cheap-model classification with a fallback intent (see PRD open issue #2).
        return IntentType.STORE;
    }

    public QueryIntent classifyQuery(String query) {
        // TODO: cheap-model sub-intent classification.
        return QueryIntent.FIND;
    }
}
