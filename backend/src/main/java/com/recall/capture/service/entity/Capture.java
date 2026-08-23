package com.recall.capture.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** capture 테이블에 대응 — 마스킹된 원문(검색 대상이 아니라 근거로 보관). */
@Entity
@Table(name = "capture")
public class Capture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자(app_user.id) — 멀티유저 격리의 앵커. memory/review 는 이 값에서 파생한다. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "raw_text", nullable = false)
    private String rawText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "masked_spans", nullable = false)
    private String maskedSpans;

    /** 비동기 저장 파이프라인의 처리 상태: PROCESSING | DONE | FAILED (조용한 실패 금지 — 실패도 상태로 노출). */
    @Column(name = "status", nullable = false)
    private String status;

    /** 실패한 파이프라인 단계(classify | extract | judge | review). 성공/처리중이면 null. */
    @Column(name = "failed_stage")
    private String failedStage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 전용 기본 생성자. */
    protected Capture() {}

    public Capture(Long userId, String sourceType, String rawText, String maskedSpans) {
        this.userId = userId;
        this.sourceType = sourceType;
        this.rawText = rawText;
        this.maskedSpans = maskedSpans;
        // 신규 캡처는 비동기 파이프라인이 아직 처리 전이므로 PROCESSING 으로 시작한다.
        this.status = CaptureStatus.PROCESSING;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getRawText() {
        return rawText;
    }

    public String getMaskedSpans() {
        return maskedSpans;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailedStage() {
        return failedStage;
    }

    public void setFailedStage(String failedStage) {
        this.failedStage = failedStage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
