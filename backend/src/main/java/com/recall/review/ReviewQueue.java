package com.recall.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single human-in-the-loop gate. Every extraction lands here as {@code pending};
 * memory is only written on approval.
 */
@Entity
@Table(name = "review_queue")
public class ReviewQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capture_id")
    private Long captureId;

    @Column(name = "memory_id")
    private Long memoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Judgement judgement;

    @Column(name = "judge_reason", columnDefinition = "text")
    private String judgeReason;

    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReviewQueue() {
    }

    public ReviewQueue(Long captureId, Judgement judgement, String judgeReason) {
        this.captureId = captureId;
        this.judgement = judgement;
        this.judgeReason = judgeReason;
    }

    public void resolve(String newStatus) {
        this.status = newStatus;
        this.resolvedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCaptureId() {
        return captureId;
    }

    public Long getMemoryId() {
        return memoryId;
    }

    public Judgement getJudgement() {
        return judgement;
    }

    public String getJudgeReason() {
        return judgeReason;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
