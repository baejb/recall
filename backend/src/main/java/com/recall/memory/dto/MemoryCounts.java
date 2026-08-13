package com.recall.memory.dto;

/** 유형 탭 카운트 — 검색어(q)가 걸리면 그 필터 기준. 첫 페이지 응답에만 실린다(이후 스크롤은 null). */
public record MemoryCounts(long total, long ts, long kn) {}
