package com.recall.memory;

import com.recall.memory.dto.MemoryResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** memory 조회 서비스. */
@Service
public class MemoryService {

    private final MemoryRepository memoryRepository;

    public MemoryService(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /** 활성 카드 목록(최신순). */
    @Transactional(readOnly = true)
    public List<MemoryResponse> listActive() {
        return memoryRepository.findByStatusOrderByCreatedAtDesc("active").stream()
                .map(MemoryService::toResponse)
                .toList();
    }

    private static MemoryResponse toResponse(Memory m) {
        return new MemoryResponse(
                m.getId(),
                m.getCapture().getId(),
                m.getType().name(),
                m.getTitle(),
                m.getSummary(),
                m.getStatus(),
                m.getCreatedAt());
    }
}
