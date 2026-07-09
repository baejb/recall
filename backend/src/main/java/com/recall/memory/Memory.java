package com.recall.memory;

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
 * A structured, approved record. Troubleshooting vs knowledge details live in the
 * {@code structured} jsonb column (not mapped here yet). Embedding / tsvector columns
 * exist in the schema but are populated by native queries, not JPA.
 */
@Entity
@Table(name = "memory")
public class Memory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryType type;

    @Column(nullable = false)
    private String title;

    private String project;

    private String component;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(nullable = false)
    private String status = "active";

    private Float confidence;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected Memory() {
    }

    public Memory(MemoryType type, String title) {
        this.type = type;
        this.title = title;
    }

    public Long getId() {
        return id;
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

    public void setProject(String project) {
        this.project = project;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Float getConfidence() {
        return confidence;
    }

    public void setConfidence(Float confidence) {
        this.confidence = confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
