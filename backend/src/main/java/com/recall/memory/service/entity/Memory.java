package com.recall.memory.service.entity;

import com.recall.capture.service.entity.Capture;
import com.recall.common.type.MemoryType;
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

    /** 소유자(app_user.id). 검색·목록이 join 없이 바로 필터하도록 capture 에서 비정규화(멀티유저 격리). */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

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
    private String status = MemoryStatus.ACTIVE;

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

    /**
     * 영속(persist)되는 카드 — 반드시 소유 capture 가 있어야 하고, user_id 는 거기서 파생한다(비동기 승인 경로에서도 스레드 컨텍스트에 의존하지
     * 않음). null capture 는 NOT NULL 위반을 flush 시점까지 늦추므로 여기서 막는다.
     */
    public Memory(Capture capture, MemoryType type, String title, String structured) {
        if (capture == null) {
            throw new IllegalArgumentException(
                    "영속 Memory 는 capture 가 필요하다 — 인메모리 카드는 transientCard 사용");
        }
        this.capture = capture;
        this.userId = capture.getUserId();
        this.type = type;
        this.title = title;
        this.structured = structured;
    }

    /**
     * 영속되지 않는 인메모리 카드 — 리랭크·답변 프롬프트 조립용으로만 쓴다(capture/user_id 없음). 이 객체를 리포지토리에 save 하면 안 된다(NOT
     * NULL 위반). 영속 경로는 위 생성자를 쓴다.
     */
    public static Memory transientCard(MemoryType type, String title, String structured) {
        Memory m = new Memory();
        m.type = type;
        m.title = title;
        m.structured = structured;
        return m;
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

    /** 상태 전이(불변 원칙: 삭제 대신 상태 보존) — active|archived|incorrect 등. */
    public void setStatus(String status) {
        this.status = status;
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
