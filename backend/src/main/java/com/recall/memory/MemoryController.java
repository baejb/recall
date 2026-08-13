package com.recall.memory;

import com.recall.memory.dto.MemoryDetailResponse;
import com.recall.memory.dto.MemoryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** memory 조회 입구. */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 활성 카드 목록. */
    @GetMapping
    public List<MemoryResponse> list() {
        return memoryService.listActive();
    }

    /** memory 상세(구조화 카드 펼침). */
    @GetMapping("/{id}")
    public MemoryDetailResponse detail(@PathVariable Long id) {
        return memoryService.getDetail(id);
    }
}
