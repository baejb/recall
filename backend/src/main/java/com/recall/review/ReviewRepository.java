package com.recall.review;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<ReviewQueue, Long> {

    List<ReviewQueue> findByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);
}
