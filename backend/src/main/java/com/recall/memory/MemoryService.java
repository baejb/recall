package com.recall.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.memory.dto.MemoryCounts;
import com.recall.memory.dto.MemoryDetailResponse;
import com.recall.memory.dto.MemoryPageResponse;
import com.recall.memory.dto.MemoryResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** memory 조회 서비스. */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    static final String STATUS_ACTIVE = "active"; // 정상
    static final String STATUS_ARCHIVED = "archived"; // 숨김(소프트 제거, 복원 가능)
    static final String STATUS_INCORRECT = "incorrect"; // 폐기(틀린 정보)
    // 사용자 액션으로 전이·조회 허용하는 상태. (superseded 는 충돌 처리에서 시스템이 설정 — 사용자 액션 대상 아님)
    private static final Set<String> ALLOWED_STATUSES =
            Set.of(STATUS_ACTIVE, STATUS_ARCHIVED, STATUS_INCORRECT);

    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryService(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /**
     * 활성 카드 한 페이지(키셋 페이지네이션). 최신순(created_at DESC · id DESC).
     *
     * @param q 제목 부분일치(대소문자 무시). 빈 값이면 필터 없음
     * @param type 유형 필터 {@code ts|kn}. 빈 값이면 전체. 그 외 값은 400
     * @param cursor 이전 페이지의 nextCursor. 빈 값이면 첫 페이지. 형식이 깨졌으면 400
     * @param limit 페이지 크기(1~50, 기본 20)
     */
    @Transactional(readOnly = true)
    public MemoryPageResponse list(String q, String type, String cursor, int limit) {
        return list(q, type, cursor, limit, STATUS_ACTIVE);
    }

    /**
     * 상태별 기억 한 페이지(키셋). status=active|archived|incorrect. 목록에서 소프트 제거(숨김·폐기)된 항목도 상태로 조회·복원할 수 있다.
     */
    @Transactional(readOnly = true)
    public MemoryPageResponse list(String q, String type, String cursor, int limit, String status) {
        String st = requireStatus(status);
        int requested = limit <= 0 ? DEFAULT_LIMIT : limit;
        int size = Math.min(Math.max(requested, 1), MAX_LIMIT);
        MemoryType typeFilter = parseType(type);
        // 빈 검색어는 "" 로 정규화한다 — LIKE '%%' 는 전체 매치라 null 분기가 필요 없고,
        // null 파라미터의 타입 추론 실패(lower(bytea))도 피한다.
        String qNorm = (q == null || q.isBlank()) ? "" : q.trim();
        MemoryCursor cur = decodeCursor(cursor);

        // limit+1 개를 요청해, 초과분 존재로 다음 페이지 유무를 한 번의 쿼리로 판단한다.
        List<Memory> rows =
                memoryRepository.findPage(
                        st,
                        typeFilter,
                        qNorm,
                        cur != null,
                        cur == null ? null : cur.createdAt(),
                        cur == null ? null : cur.id(),
                        PageRequest.of(0, size + 1));

        boolean hasMore = rows.size() > size;
        List<Memory> page = hasMore ? rows.subList(0, size) : rows;
        List<MemoryResponse> items = page.stream().map(MemoryService::toResponse).toList();

        String nextCursor = null;
        if (hasMore) {
            Memory last = page.get(page.size() - 1);
            nextCursor = new MemoryCursor(last.getCreatedAt(), last.getId()).encode();
        }

        // 카운트는 첫 페이지(커서 없음)에서만 계산한다 — 스크롤마다 재계산하지 않는다(프론트가 첫 값을 유지).
        MemoryCounts counts = null;
        if (cur == null) {
            counts =
                    new MemoryCounts(
                            memoryRepository.countByStatus(st, null, qNorm),
                            memoryRepository.countByStatus(st, MemoryType.TROUBLESHOOTING, qNorm),
                            memoryRepository.countByStatus(st, MemoryType.KNOWLEDGE, qNorm));
        }
        return new MemoryPageResponse(items, nextCursor, counts);
    }

    /**
     * 기억 상태 전이(불변 원칙: 삭제 대신 상태 보존). active↔archived(숨김)↔incorrect(폐기)를 자유롭게 오갈 수 있어, 소프트 제거·복원·폐기를
     * 모두 상태로 표현한다. 없는 id는 404, 허용되지 않은 status는 400.
     */
    @Transactional
    public MemoryDetailResponse updateStatus(Long id, String status) {
        String st = requireStatus(status);
        Memory memory =
                memoryRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "없는 memory: " + id));
        memory.setStatus(st);
        return toDetailResponse(memoryRepository.save(memory));
    }

    private static String requireStatus(String status) {
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 status: " + status);
        }
        return status;
    }

    /**
     * 프론트 유형 키(ts|kn) → MemoryType. 빈 값=필터 없음(null), 그 외=400. 외부 입력을 enum 으로 옮기는 경계 파싱이며, 유형별 비즈니스
     * 분기(switch(MemoryType)) 가 아니다.
     */
    private static MemoryType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type) {
            case "ts" -> MemoryType.TROUBLESHOOTING;
            case "kn" -> MemoryType.KNOWLEDGE;
            default ->
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 type: " + type);
        };
    }

    private static MemoryCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return MemoryCursor.decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 커서");
        }
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
