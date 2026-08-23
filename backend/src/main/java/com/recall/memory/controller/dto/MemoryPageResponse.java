package com.recall.memory.controller.dto;

import java.util.List;

/**
 * 기억 목록 한 페이지 — 키셋 페이지네이션 응답.
 *
 * @param items 이 페이지의 카드(최대 limit개, created_at DESC · id DESC)
 * @param nextCursor 다음 페이지 시작 커서. {@code null} 이면 마지막 페이지(더 없음)
 * @param counts 유형 탭 카운트. 첫 페이지(cursor 없음)에만 채워지고 이후 스크롤 응답엔 {@code null}
 */
public record MemoryPageResponse(
        List<MemoryResponse> items, String nextCursor, MemoryCounts counts) {}
