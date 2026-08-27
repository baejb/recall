package com.recall.auth.service;

import com.recall.auth.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 로그인한 OAuth 주체 → {@code app_user} 행. <b>허용목록 판정과 계정 생성이 같은 자리에서 일어난다</b>.
 *
 * <p>둘을 붙여 둔 이유: 판정과 생성이 떨어져 있으면 "판정은 했는데 생성 경로가 그걸 안 보는" 구멍이 생긴다. 여기가 계정이 만들어지는 <b>유일한 경로</b>이고, 그
 * 입구에서 거절한다(fail-closed).
 *
 * <p><b>기존 부트스트랩 데이터는 이어받지 않는다</b>(팀 결정) — 로그인 사용자는 {@code id=2} 부터 새로 만들어지고, 부트스트랩 사용자(id=1)가 갖고
 * 있던 기억·설정은 지우지 않되 아무에게도 보이지 않는다. "먼저 로그인한 사람이 남의 기억을 얻는" 경로를 아예 만들지 않는 쪽을 택했다.
 *
 * <p>DB 쓰기는 {@link AppUserWriter} 가 자기 트랜잭션에서 한다 — 그 이유는 그쪽 javadoc 에 있다(유니크 위반 후 재조회).
 */
@Service
public class AppUserProvisioning {

    private static final Logger log = LoggerFactory.getLogger(AppUserProvisioning.class);

    private final AppUserWriter writer;
    private final AuthProperties authProperties;

    AppUserProvisioning(AppUserWriter writer, AuthProperties authProperties) {
        this.writer = writer;
        this.authProperties = authProperties;
    }

    /**
     * 허용목록을 통과한 주체의 {@code app_user.id}. 없으면 만든다.
     *
     * <p><b>동시 첫 로그인을 견딘다</b> — 조회 후 생성(check-then-act)이라 첫 로그인 콜백이 겹치면(새로고침·더블클릭·브라우저 프리페치) 둘 다 빈
     * 결과를 보고 둘 다 insert 한다. 하나는 {@code uq_app_user_provider_subject} 에 걸리는데, 그건 결함이 아니라 <b>상대가 먼저
     * 만들었다</b>는 사실이다 — 새 트랜잭션에서 다시 조회하면 그 행이 있다. 이 재시도가 없으면 {@code DataIntegrityViolationException}
     * 이 그대로 올라가고, 그건 {@code AuthenticationException} 이 아니라서 로그인 실패 핸들러를 타지 못해 스프링 기본 흰 500 페이지가 된다.
     *
     * @param provider OAuth provider 이름(google 등)
     * @param subject OAuth {@code sub} — provider 안에서 불변인 식별자
     * @param email provider 가 <b>검증한</b> 이메일. 허용목록 판정 대상
     * @param displayName 표시 이름(없으면 빈 문자열)
     * @throws LoginNotAllowedException 허용목록에 없는 이메일 — 계정을 만들지 않는다
     */
    public long resolveOrCreate(String provider, String subject, String email, String displayName) {
        if (!authProperties.allows(email)) {
            // 이메일을 로그에 남긴다: 누가 막혔는지 모르면 "왜 로그인이 안 되냐"는 문의를 풀 수 없다.
            // 이메일은 자격증명이 아니라 식별자이므로 마스킹 대상이 아니다(원문 마스킹과 다른 축).
            log.warn("허용목록에 없는 로그인 시도 — provider={} email={}", provider, email);
            throw new LoginNotAllowedException(email);
        }

        try {
            return writer.resolveOrCreate(provider, subject, email, displayName);
        } catch (DataIntegrityViolationException raced) {
            log.info("동시 첫 로그인 — 먼저 만들어진 행을 쓴다: provider={} email={}", provider, email);
            return writer.resolveOrCreate(provider, subject, email, displayName);
        }
    }
}
