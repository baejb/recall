package com.recall.capture;

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

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "raw_text", nullable = false)
    private String rawText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "masked_spans", nullable = false)
    private String maskedSpans;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 전용 기본 생성자. */
    protected Capture() {}

    public Capture(String sourceType, String rawText, String maskedSpans) {
        this.sourceType = sourceType;
        this.rawText = rawText;
        this.maskedSpans = maskedSpans;
    }

    public Long getId() {
        return id;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
