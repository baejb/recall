package com.recall.auth.service;

import com.recall.auth.repository.AppUserRepository;
import com.recall.auth.service.entity.AppUser;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code app_user} 조회/생성의 <b>트랜잭션 경계</b>. {@link AppUserProvisioning} 이 이 빈을 통해 부른다.
 *
 * <p><b>왜 별 클래스인가</b>(껍데기 서비스를 만들지 않는다는 규칙의 예외) — 첫 로그인이 동시에 두 번 오면 둘 다 빈 결과를 보고 둘 다 insert 하고, 하나가
 * {@code uq_app_user_provider_subject} 에 걸린다. 그 유니크 위반을 잡아 <b>다시 조회</b>하려면 실패한 트랜잭션이 이미 끝나 있어야 한다:
 * JPA 는 제약 위반 뒤 같은 {@code EntityManager} 로 계속할 수 없고, 트랜잭션은 rollback-only 로 표시된다. 그런데 같은 빈 안에서 자기
 * 메서드를 부르면 프록시를 지나지 않아 트랜잭션 경계가 생기지 않는다. 그래서 재시도하는 쪽과 트랜잭션을 여는 쪽을 <b>다른 빈</b>으로 나눈다.
 */
@Service
class AppUserWriter {

    private static final Logger log = LoggerFactory.getLogger(AppUserWriter.class);

    private final AppUserRepository users;

    AppUserWriter(AppUserRepository users) {
        this.users = users;
    }

    /**
     * {@code (provider, subject)} 로 찾고 없으면 만든 뒤, 로그인 흔적을 남기고 id 를 준다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 동시 첫 로그인에서 상대가 먼저 만든 경우(호출자가
     *     재시도한다)
     */
    @Transactional
    long resolveOrCreate(String provider, String subject, String email, String displayName) {
        AppUser user =
                users.findByProviderAndSubject(provider, subject)
                        .orElseGet(
                                () -> {
                                    log.info("새 사용자 생성 — provider={} email={}", provider, email);
                                    return users.save(
                                            new AppUser(provider, subject, email, displayName));
                                });
        user.recordLogin(email, displayName, OffsetDateTime.now());
        return user.getId();
    }
}
