package com.recall.review.repository;

import com.recall.review.service.entity.ReviewItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** review_queue 저장/조회 창구. */
public interface ReviewRepository extends JpaRepository<ReviewItem, Long> {

    /** 특정 상태의 검토 항목을 오래된 순으로(대기함 목록) — 사용자 스코프. */
    List<ReviewItem> findByUserIdAndStatusOrderByCreatedAtAsc(long userId, String status);

    /**
     * 처리된(승인·반려) 검토 항목을 <b>최근 처리순</b>으로 — 사용자 스코프.
     *
     * <p>대기함과 정렬 방향이 반대인 이유: 대기함은 먼저 온 것부터 처리하는 큐라 오래된 순이 맞고, 처리 기록은 "방금 뭘 했더라"를 되짚는 자리라 최근 것이 위여야
     * 한다.
     */
    List<ReviewItem> findByUserIdAndStatusInOrderByResolvedAtDesc(
            long userId, Collection<String> statuses);

    /** 특정 상태 개수(대기 건수 배지 등) — 사용자 스코프. */
    long countByUserIdAndStatus(long userId, String status);

    /** 승인/반려를 소유자 스코프로 — 남의 검토 항목 id 를 넘겨도 처리되지 않는다(교차유출 금지). */
    Optional<ReviewItem> findByIdAndUserId(Long id, long userId);
}
