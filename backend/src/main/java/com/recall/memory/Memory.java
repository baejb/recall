package com.recall.memory;

import com.recall.capture.Capture;
import com.recall.common.MemoryType;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** memory 테이블에 대응 — 승인된 구조화 카드. 원문 1개에 여러 개(1:N). */
@Entity
@Table(name = "memory")
public class Memory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 카드가 나온 원문. 여러 memory가 한 capture를 가리킴(1:N). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capture_id", nullable = false)
    private Capture capture;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private MemoryType type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "project")
    private String project;

    @Column(name = "component")
    private String component;

    @Column(name = "summary")
    private String summary;

    /** 유형별 필드(증상/원인/해결 또는 사실/문서)를 JSON 상자로. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured", nullable = false)
    private String structured;

    /** 삭제 대신 상태 전이(불변 원칙): active | superseded | incorrect. */
    @Column(name = "status", nullable = false)
    private String status = "active";

    @Column(name = "confidence")
    private Double confidence;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 전용 기본 생성자. */
    protected Memory() {}

    public Memory(Capture capture, MemoryType type, String title, String structured) {
        this.capture = capture;
        this.type = type;
        this.title = title;
        this.structured = structured;
    }

    public Long getId() {
        return id;
    }

    public Capture getCapture() {
        return capture;
    }

    public MemoryType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getProject() {
        return project;
    }

    public String getComponent() {
        return component;
    }

    public String getSummary() {
        return summary;
    }

    public String getStructured() {
        return structured;
    }

    public String getStatus() {
        return status;
    }

    public Double getConfidence() {
        return confidence;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
