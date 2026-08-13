package com.recall.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.memory.dto.MemoryDetailResponse;
import com.recall.memory.dto.MemoryResponse;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** memory 조회 서비스. */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /** memory 상세 — structured(승인된 카드 JSON)를 펼쳐서 반환한다. 없는 id는 404. */
    @Transactional(readOnly = true)
    public MemoryDetailResponse getDetail(Long id) {
        Memory memory =
                memoryRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "없는 memory: " + id));
        return toDetailResponse(memory);
    }

    private MemoryDetailResponse toDetailResponse(Memory memory) {
        Map<String, Object> structured = parseStructured(memory);

        String title = memory.getTitle();
        if (title == null || title.isBlank()) {
            title = stringField(structured, "title");
        }

        String summary = memory.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = stringField(structured, "summary");
        }

        return new MemoryDetailResponse(
                memory.getId(),
                memory.getCapture().getId(),
                memory.getType().name(),
                title,
                summary,
                stringList(structured, "keywords"),
                stringList(structured, "facts"),
                stringField(structured, "document"),
                memory.getStatus(),
                memory.getCreatedAt());
    }

    /** structured 파싱 실패는 memory 조회 자체를 막지 않는다 — 빈 구조로 방어(조용한 실패 대신 로그로 드러냄). */
    private Map<String, Object> parseStructured(Memory memory) {
        try {
            return objectMapper.readValue(
                    memory.getStructured(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("memory structured 파싱 실패 memoryId={}: {}", memory.getId(), e.getMessage());
            return Map.of();
        }
    }

    private static String stringField(Map<String, Object> structured, String key) {
        Object value = structured.get(key);
        return value == null ? null : value.toString();
    }

    private static List<String> stringList(Map<String, Object> structured, String key) {
        Object value = structured.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
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
