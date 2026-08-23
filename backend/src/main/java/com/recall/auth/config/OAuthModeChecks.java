package com.recall.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code oauth} 프로필 부팅 시 오설정을 <b>부팅 실패나 경고로</b> 드러낸다. 이 검사들이 없으면 각 상황이 "로그인이 안 된다"·"내 기억이 사라졌다" 같은
 * 증상으로만 나타나고 원인을 찾는 데 시간이 든다.
 */
@Configuration
@Profile("oauth")
public class OAuthModeChecks {

    private static final Logger log = LoggerFactory.getLogger(OAuthModeChecks.class);

    private final AuthProperties authProperties;

    public OAuthModeChecks(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /**
     * 허용목록이 비어 있으면 <b>부팅을 막는다</b>.
     *
     * <p>비어 있으면 모든 로그인이 403 이다 — 즉 아무도 못 들어오는 인스턴스가 정상 부팅해서, 증상은 "로그인 버튼을 눌러도 되돌아온다"로만 나타난다.
     * fail-closed 정책은 유지하면서, <b>설정을 잊은 것</b>과 <b>의도적으로 아무도 허용하지 않은 것</b>을 구분할 방법이 없으므로 부팅에서 막는 쪽을
     * 택했다.
     */
    @Bean
    ApplicationRunner allowedEmailsCheck() {
        return args -> {
            if (authProperties.normalizedAllowedEmails().isEmpty()) {
                throw new IllegalStateException(
                        "oauth 프로필인데 허용 이메일이 비어 있다 — 아무도 로그인할 수 없다."
                                + " RECALL_ALLOWED_EMAILS 를 설정하라(쉼표 구분).");
            }
            log.info("로그인 허용 이메일 {}개 설정됨", authProperties.normalizedAllowedEmails().size());
        };
    }

    /**
     * 부트스트랩 사용자(id=1)가 데이터를 갖고 있으면 <b>경고한다</b>.
     *
     * <p>팀 결정에 따라 로그인 사용자는 새로 만들어지고(id=2 부터) 부트스트랩 데이터를 이어받지 않는다. 그래서 인증을 켠 직후 화면이 <b>빈 목록</b>으로
     * 보이는데, 그게 "데이터가 사라진 것"으로 오해되기 쉽다. 지우지 않았고 소유자가 달라 보이지 않을 뿐이라는 사실을 부팅 로그에 남긴다.
     */
    @Bean
    ApplicationRunner bootstrapDataNotice(JdbcTemplate jdbc) {
        return args -> {
            Integer memories =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM memory WHERE user_id = 1", Integer.class);
            Integer captures =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM capture WHERE user_id = 1", Integer.class);
            if ((memories != null && memories > 0) || (captures != null && captures > 0)) {
                log.warn(
                        "부트스트랩 사용자(id=1) 소유 데이터가 있다 — memory {}건, capture {}건."
                                + " 로그인 사용자는 새로 만들어지므로 이 데이터는 화면에 보이지 않는다(지워지지는 않았다).",
                        memories,
                        captures);
            }
        };
    }
}
