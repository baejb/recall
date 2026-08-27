package com.recall.auth.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 로그인 사용자({@code app_user}, V9). 이 테이블의 소유 모듈은 {@code auth} 다 — 다른 모듈은 {@code user_id} 값만 들고 있고
 * 엔티티를 보지 않는다(모듈 경계: FK 는 id 컬럼으로).
 *
 * <p>식별은 {@code (provider, subject)} 다 — 이메일이 아니다. 이메일은 provider 쪽에서 바뀔 수 있고(계정 이관·별칭), 그걸 키로 쓰면 같은
 * 사람이 다른 사용자가 되거나 남의 데이터에 붙을 수 있다. OAuth {@code sub} 는 provider 안에서 불변이라 그걸 키로 쓴다. 이메일은 표시·허용목록
 * 판정용으로만 저장한다.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String subject;

    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    protected AppUser() {} // JPA

    public AppUser(String provider, String subject, String email, String displayName) {
        this.provider = provider;
        this.subject = subject;
        this.email = email;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    /**
     * 로그인 시각을 기록하고, provider 가 준 표시 정보를 최신으로 맞춘다.
     *
     * <p>이메일·이름을 매 로그인마다 갱신하는 이유: 사용자가 provider 쪽에서 바꿨을 때 화면에 옛 값이 남으면 "누구로 로그인했는지"를 잘못 알려준다. 식별자
     * ({@code subject})는 건드리지 않는다.
     */
    public void recordLogin(String email, String displayName, OffsetDateTime now) {
        this.email = email;
        this.displayName = displayName;
        this.lastLoginAt = now;
    }
}
