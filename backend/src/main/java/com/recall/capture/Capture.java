package com.recall.capture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The original pasted conversation/document, masked before extraction.
 * Kept as evidence and as the source for re-extraction; never fed to search indexes.
 */
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

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Capture() {
    }

    public Capture(String sourceType, String rawText) {
        this.sourceType = sourceType;
        this.rawText = rawText;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
