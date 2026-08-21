package com.recall.review;

import com.recall.capture.Capture;
import com.recall.common.MemoryType;
import com.recall.memory.Memory;
import com.recall.memory.type.Verdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** review_queue 테이블에 대응 — 유일한 승인 게이트. 승인 전에는 memory에 반영하지 않는다(불변 원칙). */
@Entity
@Table(name = "review_queue")
public class ReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자(app_user.id) — capture 에서 파생. 대기함 조회를 사용자별로 스코프한다. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capture_id", nullable = false)
    private Capture capture;

    /** 재발/충돌 판정의 대상 기존 memory. 신규(NEW)면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memory_id")
    private Memory memory;

    /** 승인 시 만들 memory의 유형(저장 파이프라인의 분류 결과). */
    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type")
    private MemoryType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "judgement", nullable = false)
    private Verdict judgement;

    @Column(name = "judge_reason")
    private String judgeReason;

    /** 승인 대기 중인 추출 구조(JSON). 승인 시 이 내용으로 memory를 만든다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed", nullable = false)
    private String proposed;

    /** pending | approved | edited | rejected. */
    @Column(name = "status", nullable = false)
    private String status = "pending";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    /** JPA 전용 기본 생성자. */
    protected ReviewItem() {}

    public ReviewItem(
            Capture capture,
            MemoryType type,
            Verdict judgement,
            Memory targetMemory,
            String judgeReason,
            String proposed) {
        this.capture = capture;
        // 소유자는 원문(capture)에서 파생 — 비동기 저장 파이프라인에서 스레드 컨텍스트에 의존하지 않는다.
        this.userId = capture.getUserId();
        this.type = type;
        this.judgement = judgement;
        this.memory = targetMemory;
        this.judgeReason = judgeReason;
        this.proposed = proposed;
    }

    /** 검토 완료 처리(승인/반려/수정). 세터 대신 의도를 드러내는 상태 전이 메서드. */
    public void resolve(String status, OffsetDateTime resolvedAt) {
        this.status = status;
        this.resolvedAt = resolvedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Capture getCapture() {
        return capture;
    }

    public Memory getMemory() {
        return memory;
    }

    public MemoryType getType() {
        return type;
    }

    public Verdict getJudgement() {
        return judgement;
    }

    public String getJudgeReason() {
        return judgeReason;
    }

    public String getProposed() {
        return proposed;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }
}
