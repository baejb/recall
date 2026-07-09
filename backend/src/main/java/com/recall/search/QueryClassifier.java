package com.recall.search;

import com.recall.intent.IntentRouter;
import com.recall.intent.QueryIntent;
import org.springframework.stereotype.Service;

/**
 * Classifies the question along the doc's axes (new vs recall, knowledge vs
 * troubleshooting, TS sub-type, ...) and extracts project/component. Scaffold delegates
 * the sub-intent to {@link IntentRouter}.
 */
@Service
public class QueryClassifier {

    private final IntentRouter intentRouter;

    public QueryClassifier(IntentRouter intentRouter) {
        this.intentRouter = intentRouter;
    }

    public QueryIntent classify(String query) {
        // TODO: full 6-axis classification + project/component extraction.
        return intentRouter.classifyQuery(query);
    }
}
