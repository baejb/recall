package com.recall.search;

import com.recall.search.dto.Candidate;
import com.recall.search.dto.QueryPlan;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Runs exact + BM25/tsvector + pgvector + metadata lookups in parallel (never serial),
 * fuses with weighted RRF, applies boost/penalty, and optionally reranks.
 */
@Service
public class HybridRetriever {

    public List<Candidate> retrieve(String query, QueryPlan plan) {
        // TODO: parallel hybrid search + weighted RRF + (rerank when plan.requiresReranker()).
        return List.of();
    }
}
