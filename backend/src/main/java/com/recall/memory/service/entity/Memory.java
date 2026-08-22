package com.recall.memory.service.entity;

import com.recall.common.type.MemoryType;
import com.recall.memory.MemoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    /**
     * 이 카드가 나온 원문의 id(여러 memory 가 한 capture 를 가리킴 — 1:N).
     *
     * <p><b>왜 연관이 아니라 id 인가</b> — {@code @ManyToOne Capture} 로 두면 memory 모듈이 capture 의 <b>엔티티
     * 클래스</b>를 알아야 하고, 그 지식이 엔티티에서 그치지 않고 행을 만드는 서비스까지 번진다(참조를 얻어야 하므로). 이 컬럼에 필요한 것은 FK 값 하나이고,
     * <b>FK 무결성은 DB 제약이 지킨다</b>(스키마는 Flyway 소유다). 매핑을 값으로 낮추면 모듈 경계가 깨끗해지고 lazy 프록시·연관 탐색이 사라져 트랜잭션
     * 밖 접근 사고도 없어진다.
     *
     * <p>대가: {@code memory.getCapture().getMaskedText()} 식의 탐색이 안 된다. 원문이 필요한 곳은 capture 모듈의 공개
     * 계약으로 물어본다(그게 모듈 경계를 지키는 방향이다).
     */
    @Column(name = "capture_id", nullable = false, updatable = false)
    private Long captureId;

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
     * 영속(persist)되는 카드.
     *
     * <p><b>{@code ownerUserId} 의 유일한 정당한 출처는 {@code capture.user_id} 다</b>(🔴 교차유출 금지). 전에는 생성자가
     * {@code Capture} 를 받아 그 파생을 <b>타입으로</b> 강제했지만, 그러면 이 엔티티가 남의 모듈 엔티티를 알아야 했다. 그래서 파생 책임을 이 모듈의
     * <b>유일한 쓰기 경로</b>({@code MemoryAccess#createApproved})로 올렸다 — 거기서 capture 의 공개 계약에 소유자를 물어
     * 넣는다. 그 계약을 우회해 직접 값을 넣으면 안 된다(회귀 테스트가 이 파생을 고정한다).
     *
     * <p>비동기 승인 경로에서도 스레드 컨텍스트(요청 스레드의 {@code CurrentUserProvider})에 의존하지 않는다는 성질은 그대로다 — 소유자는 언제나
     * DB 의 capture 행에서 온다.
     */
    public Memory(
            long captureId, long ownerUserId, MemoryType type, String title, String structured) {
        this.captureId = captureId;
        this.userId = ownerUserId;
        this.type = type;
        this.title = title;
        this.structured = structured;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCaptureId() {
        return captureId;
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
