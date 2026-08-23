package com.recall.auth.service;

import com.recall.auth.config.AuthProperties;
import com.recall.auth.repository.AppUserRepository;
import com.recall.auth.service.entity.AppUser;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 OAuth 주체 → {@code app_user} 행. <b>허용목록 판정과 계정 생성이 같은 자리에서 일어난다</b>.
 *
 * <p>둘을 붙여 둔 이유: 판정과 생성이 떨어져 있으면 "판정은 했는데 생성 경로가 그걸 안 보는" 구멍이 생긴다. 여기가 계정이 만들어지는 <b>유일한 경로</b>이고, 그
 * 입구에서 거절한다(fail-closed).
 *
 * <p><b>기존 부트스트랩 데이터는 이어받지 않는다</b>(팀 결정) — 로그인 사용자는 {@code id=2} 부터 새로 만들어지고, 부트스트랩 사용자(id=1)가 갖고
 * 있던 기억·설정은 지우지 않되 아무에게도 보이지 않는다. "먼저 로그인한 사람이 남의 기억을 얻는" 경로를 아예 만들지 않는 쪽을 택했다.
 */
@Service
public class AppUserProvisioning {

    private static final Logger log = LoggerFactory.getLogger(AppUserProvisioning.class);

    private final AppUserRepository users;
    private final AuthProperties authProperties;

    public AppUserProvisioning(AppUserRepository users, AuthProperties authProperties) {
        this.users = users;
        this.authProperties = authProperties;
    }

    /**
     * 허용목록을 통과한 주체의 {@code app_user.id}. 없으면 만든다.
     *
     * @param provider OAuth provider 이름(google 등)
     * @param subject OAuth {@code sub} — provider 안에서 불변인 식별자
     * @param email provider 가 <b>검증한</b> 이메일. 허용목록 판정 대상
     * @param displayName 표시 이름(없으면 빈 문자열)
     * @throws LoginNotAllowedException 허용목록에 없는 이메일 — 계정을 만들지 않는다
     */
    @Transactional
    public long resolveOrCreate(String provider, String subject, String email, String displayName) {
        if (!authProperties.allows(email)) {
            // 이메일을 로그에 남긴다: 누가 막혔는지 모르면 "왜 로그인이 안 되냐"는 문의를 풀 수 없다.
            // 이메일은 자격증명이 아니라 식별자이므로 마스킹 대상이 아니다(원문 마스킹과 다른 축).
            log.warn("허용목록에 없는 로그인 시도 — provider={} email={}", provider, email);
            throw new LoginNotAllowedException(email);
        }

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
