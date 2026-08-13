package com.recall.memory;

import com.recall.memory.dto.MemoryDetailResponse;
import com.recall.memory.dto.MemoryPageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** memory 조회 입구. */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 활성 카드 목록(키셋 페이지네이션). 최신순. q=제목 검색, type=ts|kn 유형 필터, cursor=다음 페이지 시작점, limit=페이지 크기. */
    @GetMapping
    public MemoryPageResponse list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return memoryService.list(q, type, cursor, limit);
    }

    /** memory 상세(구조화 카드 펼침). */
    @GetMapping("/{id}")
    public MemoryDetailResponse detail(@PathVariable Long id) {
        return memoryService.getDetail(id);
    }
}
