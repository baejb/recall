package com.recall.typerouter;

import com.recall.llm.LlmClient;
import com.recall.memory.MemoryRepository;
import com.recall.review.Judgement;
import org.springframework.stereotype.Service;

/**
 * Two-stage reentry judgement: vector similarity narrows candidates, then the LLM makes
 * the final call (never similarity alone). Conflict detection is a separate LLM call.
 */
@Service
public class ReentryJudge {

    private final MemoryRepository memoryRepository;
    private final LlmClient llm;

    public ReentryJudge(MemoryRepository memoryRepository, LlmClient llm) {
        this.memoryRepository = memoryRepository;
        this.llm = llm;
    }

    public Judgement judge(String extractionJson) {
        // TODO: narrow candidates by embedding similarity, then LLM judge:
        //       NEW / RECURRENCE / SUPPLEMENT / CONFLICT.
        return Judgement.NEW;
    }
}
