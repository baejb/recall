package com.recall.query.dto;

import jakarta.validation.constraints.NotBlank;

/** 조회 질문. */
public record QueryRequest(@NotBlank String question) {}
