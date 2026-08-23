package com.recall.review.controller.dto;

/** 승인 결과 — 새로 만들어진 memory 의 id. 전에는 {@code Map.of("memoryId", id)} 였다. */
public record ApprovedResponse(long memoryId) {}
