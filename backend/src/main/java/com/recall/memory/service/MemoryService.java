package com.recall.memory.service;

import com.recall.common.config.CurrentUserProvider;
import com.recall.common.exception.NotFoundException;
import com.recall.common.exception.ValidationException;
import com.recall.common.type.MemoryType;
import com.recall.memory.MemoryStatus;
import com.recall.memory.controller.dto.MemoryCounts;
import com.recall.memory.controller.dto.MemoryDetailResponse;
import com.recall.memory.controller.dto.MemoryPageResponse;
import com.recall.memory.controller.dto.MemoryResponse;
import com.recall.memory.repository.MemoryRepository;
import com.recall.memory.service.entity.Memory;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.MemoryCard;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** memory 조회 서비스. */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    // 상태 어휘는 memory 도메인의 MemoryStatus 가 소유한다 — 전에는 이 클래스의 package-private 상수,
    // Memory 엔티티의 필드 초기화, MemoryController 의 defaultValue, MemorySearchStore 의 native SQL 이
    // 각자 리터럴을 갖고 있어 어휘를 옮길 때 grep 이 유일한 안전망이었다.

    private final MemoryRepository memoryRepository;
    private final CurrentUserProvider currentUser;
    private final CardCodec cardCodec;

    public MemoryService(
            MemoryRepository memoryRepository,
            CurrentUserProvider currentUser,
            CardCodec cardCodec) {
        this.memoryRepository = memoryRepository;
        this.currentUser = currentUser;
        this.cardCodec = cardCodec;
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
        return list(q, type, cursor, limit, MemoryStatus.ACTIVE);
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
        long userId = currentUser.currentUserId();

        // limit+1 개를 요청해, 초과분 존재로 다음 페이지 유무를 한 번의 쿼리로 판단한다.
        List<Memory> rows =
                memoryRepository.findPage(
                        userId,
                        st,
                        typeFilter,
                        qNorm,
                        cur != null,
                        cur == null ? null : cur.createdAt(),
                        cur == null ? null : cur.id(),
                        PageRequest.of(0, size + 1));

        boolean hasMore = rows.size() > size;
        List<Memory> page = hasMore ? rows.subList(0, size) : rows;
        List<MemoryResponse> items = page.stream().map(this::toResponse).toList();

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
                            memoryRepository.countByStatus(userId, st, null, qNorm),
                            memoryRepository.countByStatus(
                                    userId, st, MemoryType.TROUBLESHOOTING, qNorm),
                            memoryRepository.countByStatus(
                                    userId, st, MemoryType.KNOWLEDGE, qNorm));
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
                        .findByIdAndUserId(id, currentUser.currentUserId())
                        .orElseThrow(() -> new NotFoundException("없는 memory: " + id));
        memory.setStatus(st);
        return toDetailResponse(memoryRepository.save(memory));
    }

    private static String requireStatus(String status) {
        if (status == null || !MemoryStatus.USER_SETTABLE.contains(status)) {
            throw new ValidationException("잘못된 status: " + status);
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
            default -> throw new ValidationException("잘못된 type: " + type);
        };
    }

    private static MemoryCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return MemoryCursor.decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("잘못된 커서");
        }
    }

    /** memory 상세 — structured(승인된 카드 JSON)를 펼쳐서 반환한다. 없는 id는 404. */
    @Transactional(readOnly = true)
    public MemoryDetailResponse getDetail(Long id) {
        Memory memory =
                memoryRepository
                        .findByIdAndUserId(id, currentUser.currentUserId())
                        .orElseThrow(() -> new NotFoundException("없는 memory: " + id));
        return toDetailResponse(memory);
    }

    private MemoryDetailResponse toDetailResponse(Memory memory) {
        // 두 경로를 나눠 쓴다:
        //  - 필드를 **읽을** 때는 카드 타입(접근자) — 전엔 "title"·"summary"·"keywords" 를 이 모듈이 문자열
        //    키로 다시 적었고, 카드 스키마가 바뀌어도 컴파일 에러 없이 조용히 비었다.
        //  - 카드 전체를 **그대로 통과**시킬 때는 원본 JSON — 카드로 읽고 다시 맵으로 바꾸면(read → toMap)
        //    그 왕복이 현재 record 에 없는 필드를 떨어뜨려, "승인된 카드 전체를 싣는다"는 structured 의
        //    계약이 깨진다(코덱이 스키마 진화를 위해 unknown 필드를 무시하도록 설정돼 있으므로).
        MemoryCard card = readCard(memory);
        Map<String, Object> structured = readRawStructured(memory);

        String title = memory.getTitle();
        if ((title == null || title.isBlank()) && card != null) {
            title = card.title();
        }

        String summary = memory.getSummary();
        if ((summary == null || summary.isBlank()) && card != null) {
            summary = card.summary();
        }
        // 카드 스키마는 널 전파를 막으려 빈 문자열로 정규화하지만, <b>API 계약은 그대로 유지</b>한다 —
        // 값 없는 선택 필드는 계속 null 로 나간다(프론트 dto: `summary: string | null`).
        // 내부 불변식을 바꿨다고 응답 모양까지 바꾸면 클라이언트 계약이 조용히 흔들린다.
        summary = blankToNull(summary);

        return new MemoryDetailResponse(
                memory.getId(),
                memory.getCaptureId(),
                memory.getType().name(),
                title,
                summary,
                card == null ? List.of() : card.keywords(),
                // facts·document 는 knowledge 전용 레거시 평면 필드다(프론트가 structured 렌더로 옮기면 제거).
                // 공유 계약(MemoryCard)에 유형별 필드를 올리지 않으려고 여기서만 맵으로 읽는다.
                stringList(structured, "facts"),
                stringField(structured, "document"),
                // 유형별 필드(트러블슈팅 symptom·attempts·root_cause 등)는 카드 전체를 그대로 실어 보낸다 —
                // 유형이 늘 때마다 DTO를 고치지 않기 위해(위 3개는 knowledge 레거시 평면 필드).
                structured,
                memory.getStatus(),
                memory.getCreatedAt());
    }

    /**
     * 저장된 카드를 유형 스키마로 되읽는다. 파싱 실패는 memory 조회 자체를 막지 않고 {@code null}로 방어한다 — 목록·상세는 조회 경로라 카드 하나가
     * 깨졌다고 화면 전체를 죽이지 않는다(조용한 실패 대신 로그로 드러냄).
     */
    private MemoryCard readCard(Memory memory) {
        try {
            return cardCodec.read(memory.getType(), memory.getStructured());
        } catch (RuntimeException e) {
            log.warn("memory structured 파싱 실패 memoryId={}: {}", memory.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 저장된 카드를 원본 그대로 읽는다 — 응답의 {@code structured} 는 "승인된 카드 전체"가 계약이므로 현재 record 가 모르는 필드도 살려서
     * 내보낸다. 파싱 실패는 {@link #readCard} 와 같은 이유로 조회를 막지 않고 빈 맵으로 방어한다.
     */
    private Map<String, Object> readRawStructured(Memory memory) {
        try {
            return cardCodec.readRaw(memory.getStructured());
        } catch (RuntimeException e) {
            log.warn("memory structured 원본 파싱 실패 memoryId={}: {}", memory.getId(), e.getMessage());
            return Map.of();
        }
    }

    /** 카드가 빈 문자열로 정규화한 선택 필드는 응답에서 다시 null 로 돌린다(API 계약 유지 — 위 summary 주석 참고). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringField(Map<String, Object> structured, String key) {
        Object value = structured.get(key);
        return value == null ? null : blankToNull(value.toString());
    }

    private static List<String> stringList(Map<String, Object> structured, String key) {
        Object value = structured.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private MemoryResponse toResponse(Memory m) {
        return new MemoryResponse(
                m.getId(),
                m.getCaptureId(),
                m.getType().name(),
                m.getTitle(),
                m.getSummary(),
                cardStatus(m),
                m.getStatus(),
                m.getCreatedAt());
    }

    /**
     * 카드 <b>내용</b>의 상태(트러블슈팅 해결 여부). 그 상태를 정의하지 않는 유형(지식)은 null.
     *
     * <p>전에는 {@code structured}의 {@code "status"} 키를 직접 읽었다. 그건 {@code "status"}라는 이름을 <b>모든 유형에
     * 예약</b>하는 규약이었고, 자기 의미의 {@code status}를 가진 유형이 붙으면 이 배지가 그 값을 해결상태로 오해한다. 지금은 카드가 {@link
     * MemoryCard#contentStatus()}로 "내용 상태를 정의한다"를 명시적으로 선언한다.
     *
     * <p>파싱 실패는 목록 조회를 막지 않고 배지만 생략한다(로그로 드러냄 — 조용한 실패 금지).
     */
    private String cardStatus(Memory m) {
        MemoryCard card = readCard(m);
        return card == null ? null : card.contentStatus().orElse(null);
    }
}
