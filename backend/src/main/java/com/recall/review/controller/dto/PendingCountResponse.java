package com.recall.review.controller.dto;

/**
 * 승인 대기 건수(배지).
 *
 * <p>전에는 {@code Map.of(ReviewStatus.PENDING, count)} 였다 — 상태 어휘를 <b>응답의 키</b>로 쓰면 어휘를 바꿀 때 API 계약이
 * 함께 바뀐다. 상태 값과 응답 스키마는 다른 축이므로 분리한다.
 */
public record PendingCountResponse(long pending) {}
