package com.recall.search.dto;

import com.recall.intent.QueryIntent;
import java.util.List;

/**
 * Output of the Search Planner: which embeddings/keyword sources to hit, their weights,
 * and metadata filters. Weights/filters are left as TODO in the scaffold.
 *
 * @param intent            resolved query sub-intent
 * @param embeddingTargets  e.g. ["problem_embedding"] for troubleshooting, ["document_embedding"] for knowledge
 * @param project           extracted project filter (nullable)
 * @param requiresReranker  COMPARE / identity questions require reranking
 */
public record QueryPlan(
        QueryIntent intent,
        List<String> embeddingTargets,
        String project,
        boolean requiresReranker
) {
}
