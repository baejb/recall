package com.recall.review;

/** Reentry judgement produced by the LLM judge on the store path. */
public enum Judgement {
    NEW,
    RECURRENCE,
    SUPPLEMENT,
    CONFLICT
}
