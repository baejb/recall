package com.recall.memory.repository;

import com.recall.common.type.MemoryType;
import com.recall.memory.MemoryStatus;
import com.recall.memory.service.entity.Memory;
import java.time.OffsetDateTime;
import java.util.Collection;
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
     * {@code userId} 소유 활성(active) memory 전체 — 사용자별 재색인(ReindexService) 기본 경로. 전역 스윕(user_id 스코프 없는
     * 전 사용자 조회)은 기본 API로 두지 않는다(잘못된 재사용 위험 — 설계 문서 §6). 전(全) 사용자 관리자 잡이 필요하면 호출부가 사용자 id 목록을 순회하며 이
     * 메서드를 사용자별로 호출한다.
     *
     * <p>메서드 이름 파생 규칙상 "find"와 "By" 사이의 낱말("Active")은 조건절이 아니라 무시되는 설명용 텍스트로 처리될 수 있어(Spring Data
     * 문서), status 조건을 이름 파생에 맡기지 않고 {@code @Query} 로 명시한다 — 상태 필터가 조용히 빠지면(전 상태 반환) 재색인이 폐기된 memory
     * 까지 재임베딩하는 조용한 정합성 버그가 된다.
     */
    // 리터럴 대신 상수 연결: MemoryStatus.ACTIVE 는 컴파일 상수라 애노테이션 값에서도 쓸 수 있다.
    // 어휘를 옮기면 이 쿼리도 함께 따라온다(전에는 grep 이 유일한 안전망이었다).
    @Query(
            "SELECT m FROM Memory m WHERE m.userId = :userId AND m.status = '"
                    + MemoryStatus.ACTIVE
                    + "'")
    List<Memory> findActiveByUserId(@Param("userId") long userId);

    /** 상세/상태전이를 소유자 스코프로 — 남의 memory id 를 넘겨도 조회되지 않는다(교차유출 금지). */
    Optional<Memory> findByIdAndUserId(Long id, long userId);

    /** id 목록을 소유자 스코프로 조회 — 남의 id 가 섞여 들어와도 그 행은 빠진다(교차유출 금지, 순서는 호출부가 복원). */
    List<Memory> findByIdInAndUserId(Collection<Long> ids, long userId);

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
