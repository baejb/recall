package com.recall.query.controller.dto;

/** 답변 조각 + 근거. text는 memoryId가 가리키는 기록에 매인다(근거 없는 생성 금지). */
public record AnswerFragment(String text, Long memoryId) {}
