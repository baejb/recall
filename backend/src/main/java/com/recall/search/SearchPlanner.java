package com.recall.search;

import com.recall.intent.QueryIntent;
import com.recall.search.dto.QueryPlan;
import java.util.List;
import org.springframework.stereotype.Service;

/** Picks search targets, weights and filters from the classified question. */
@Service
public class SearchPlanner {

    public QueryPlan plan(String query, QueryIntent intent) {
        // TODO: choose embedding targets + per-source weights + metadata filters per doc 4.5.
        boolean requiresReranker = intent == QueryIntent.COMPARE;
        return new QueryPlan(intent, List.of("problem_embedding"), null, requiresReranker);
    }
}
