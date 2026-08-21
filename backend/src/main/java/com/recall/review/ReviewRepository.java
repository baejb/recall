package com.recall.review;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** review_queue 저장/조회 창구. */
public interface ReviewRepository extends JpaRepository<ReviewItem, Long> {

    /** 특정 상태의 검토 항목을 오래된 순으로(대기함 목록) — 사용자 스코프. */
    List<ReviewItem> findByUserIdAndStatusOrderByCreatedAtAsc(long userId, String status);

    /** 특정 상태 개수(대기 건수 배지 등) — 사용자 스코프. */
    long countByUserIdAndStatus(long userId, String status);

    /** 승인/반려를 소유자 스코프로 — 남의 검토 항목 id 를 넘겨도 처리되지 않는다(교차유출 금지). */
    Optional<ReviewItem> findByIdAndUserId(Long id, long userId);
}
