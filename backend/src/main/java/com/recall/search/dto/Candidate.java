package com.recall.search.dto;

public record Candidate(Long memoryId, double score, String snippet) {
}
