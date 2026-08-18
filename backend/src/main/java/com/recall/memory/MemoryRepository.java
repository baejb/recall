package com.recall.memory;

import com.recall.common.MemoryType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** memory 저장/조회 창구. */
public interface MemoryRepository extends JpaRepository<Memory, Long> {

    /** 유형별 활성 카드 조회(메서드 이름만으로 쿼리 자동 생성). */
    List<Memory> findByTypeAndStatus(MemoryType type, String status);

    /**
     * ⚠️ 전(全) 사용자 스윕(user_id 스코프 없음) — 재색인 배경잡(ReindexService) 전용. 사용자 대면 목록에는 쓰지 말 것(교차유출). 사용자별
     * 목록은 {@link #findPage} 를 쓴다. 전역 인덱스(idx_memory_status_created_id)가 이 쿼리를 서빙한다.
     */
    List<Memory> findByStatusOrderByCreatedAtDesc(String status);

    /** 상세/상태전이를 소유자 스코프로 — 남의 memory id 를 넘겨도 조회되지 않는다(교차유출 금지). */
    Optional<Memory> findByIdAndUserId(Long id, long userId);

    /**
     * 활성 기억 한 페이지(키셋). 정렬 {@code created_at DESC, id DESC}.
     *
     * <p>파라미터는 모두 선택적이며 null 이면 해당 조건이 통과한다: {@code type}=유형 필터, {@code q}=제목 부분일치(대소문자 무시), {@code
     * (curTs, curId)}=커서(이 키 "다음"부터). 커서 조건은 타이브레이커까지 포함한다: {@code created_at < curTs OR
     * (created_at = curTs AND id < curId)}. 결정론 단계(LLM 없음).
     *
     * <p>호출부는 {@code Pageable} 로 {@code limit + 1} 개를 요청해, 초과분 존재로 다음 페이지 유무를 판단한다.
     */
    @Query(
            """
            SELECT m FROM Memory m
            WHERE m.userId = :userId
              AND m.status = :status
              AND (:type IS NULL OR m.type = :type)
              AND lower(m.title) LIKE lower(concat('%', :q, '%'))
              AND (:seek = false
                   OR m.createdAt < :curTs
                   OR (m.createdAt = :curTs AND m.id < :curId))
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<Memory> findPage(
            @Param("userId") long userId,
            @Param("status") String status,
            @Param("type") MemoryType type,
            @Param("q") String q,
            @Param("seek") boolean seek,
            @Param("curTs") OffsetDateTime curTs,
            @Param("curId") Long curId,
            Pageable pageable);

    /** 상태별 기억 카운트(유형 탭용). type=null 이면 전체, q 가 있으면 제목 필터 기준. 사용자 스코프. */
    @Query(
            """
            SELECT count(m) FROM Memory m
            WHERE m.userId = :userId
              AND m.status = :status
              AND (:type IS NULL OR m.type = :type)
              AND lower(m.title) LIKE lower(concat('%', :q, '%'))
            """)
    long countByStatus(
            @Param("userId") long userId,
            @Param("status") String status,
            @Param("type") MemoryType type,
            @Param("q") String q);
}
