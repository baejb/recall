package com.recall.memory.controller;

import com.recall.common.web.ApiResponse;
import com.recall.memory.controller.dto.MemoryDetailResponse;
import com.recall.memory.controller.dto.MemoryPageResponse;
import com.recall.memory.controller.dto.MemoryStatusRequest;
import com.recall.memory.service.MemoryService;
import com.recall.memory.service.entity.MemoryStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 활성 카드 목록(키셋 페이지네이션). 최신순. q=제목 검색, type=ts|kn, cursor=다음 페이지, limit=페이지 크기, status=조회할 상태(기본
     * active — 숨김·폐기 항목은 status=archived|incorrect 로 조회).
     */
    @GetMapping
    public ApiResponse<MemoryPageResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = MemoryStatus.ACTIVE) String status) {
        return ApiResponse.ok(memoryService.list(q, type, cursor, limit, status));
    }

    /** memory 상세(구조화 카드 펼침). */
    @GetMapping("/{id}")
    public ApiResponse<MemoryDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(memoryService.getDetail(id));
    }

    /**
     * 상태 전이(삭제 대신 상태 보존) — active(복원)·archived(숨김)·incorrect(폐기). 하드 삭제는 없다: 목록에서 사라져도 DB엔 보존되어 언제든
     * 복원할 수 있다.
     */
    @PatchMapping("/{id}/status")
    public ApiResponse<MemoryDetailResponse> updateStatus(
            @PathVariable Long id, @Valid @RequestBody MemoryStatusRequest request) {
        return ApiResponse.ok(memoryService.updateStatus(id, request.status()));
    }
}
