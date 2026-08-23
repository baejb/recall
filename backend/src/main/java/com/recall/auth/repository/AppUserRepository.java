package com.recall.auth.repository;

import com.recall.auth.service.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code app_user} 접근. 이 리포지토리는 auth 모듈 안에서만 쓴다(모듈 밖에는 {@code AuthAccess} 로 낸다). */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** OAuth 식별자로 조회 — 로그인 시 "이미 있는 사용자인가"를 판정하는 유일한 기준. */
    Optional<AppUser> findByProviderAndSubject(String provider, String subject);
}
