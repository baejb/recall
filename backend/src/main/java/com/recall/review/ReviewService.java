package com.recall.review;

import com.recall.common.audit.AuditLog;
import com.recall.common.audit.AuditRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final AuditRepository auditRepository;

    public ReviewService(ReviewRepository reviewRepository, AuditRepository auditRepository) {
        this.reviewRepository = reviewRepository;
        this.auditRepository = auditRepository;
    }

    /** Store-path result lands here as pending — never straight into memory. */
    @Transactional
    public ReviewQueue enqueue(Long captureId, Judgement judgement, String reason) {
        ReviewQueue item = reviewRepository.save(new ReviewQueue(captureId, judgement, reason));
        auditRepository.save(new AuditLog("review.enqueue", "review_queue", item.getId()));
        return item;
    }

    public List<ReviewQueue> pending() {
        return reviewRepository.findByStatusOrderByCreatedAtDesc("pending");
    }

    public long pendingCount() {
        return reviewRepository.countByStatus("pending");
    }

    @Transactional
    public void approve(Long id) {
        ReviewQueue item = reviewRepository.findById(id).orElseThrow();
        // TODO: persist Memory + memory_capture link + generate embeddings in one transaction.
        item.resolve("approved");
        auditRepository.save(new AuditLog("review.approve", "review_queue", id));
    }

    @Transactional
    public void reject(Long id) {
        ReviewQueue item = reviewRepository.findById(id).orElseThrow();
        item.resolve("rejected");
        auditRepository.save(new AuditLog("review.reject", "review_queue", id));
    }
}
