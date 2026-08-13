package com.recall.memory;

import com.recall.common.MemoryType;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** memory 저장/조회 창구. */
public interface MemoryRepository extends JpaRepository<Memory, Long> {

    /** 유형별 활성 카드 조회(메서드 이름만으로 쿼리 자동 생성). */
    List<Memory> findByTypeAndStatus(MemoryType type, String status);

    /** 상태별 카드를 최신순으로(목록 조회). */
    List<Memory> findByStatusOrderByCreatedAtDesc(String status);

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
            WHERE m.status = 'active'
              AND (:type IS NULL OR m.type = :type)
              AND lower(m.title) LIKE lower(concat('%', :q, '%'))
              AND (:seek = false
                   OR m.createdAt < :curTs
                   OR (m.createdAt = :curTs AND m.id < :curId))
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<Memory> findActivePage(
            @Param("type") MemoryType type,
            @Param("q") String q,
            @Param("seek") boolean seek,
            @Param("curTs") OffsetDateTime curTs,
            @Param("curId") Long curId,
            Pageable pageable);

    /** 활성 기억 카운트(유형 탭용). type=null 이면 전체, q 가 있으면 제목 필터 기준. */
    @Query(
            """
            SELECT count(m) FROM Memory m
            WHERE m.status = 'active'
              AND (:type IS NULL OR m.type = :type)
              AND lower(m.title) LIKE lower(concat('%', :q, '%'))
            """)
    long countActive(@Param("type") MemoryType type, @Param("q") String q);
}
