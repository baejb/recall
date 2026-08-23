package com.recall.search;

/** 검색 채널이 매긴 점수가 붙은 memory 참조. */
public record ScoredMemory(Long memoryId, double score) {}
