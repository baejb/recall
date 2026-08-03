package com.recall.review;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** review_queue 저장/조회 창구. */
public interface ReviewRepository extends JpaRepository<ReviewItem, Long> {

    /** 특정 상태의 검토 항목을 오래된 순으로(대기함 목록). */
    List<ReviewItem> findByStatusOrderByCreatedAtAsc(String status);

    /** 특정 상태 개수(대기 건수 배지 등). */
    long countByStatus(String status);
}
