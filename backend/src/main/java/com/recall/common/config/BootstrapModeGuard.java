package com.recall.common.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 부트스트랩 모드(인증 없음)를 <b>명시적 opt-in 이 있을 때만</b> 허용한다. 없으면 부팅을 막는다.
 *
 * <p><b>왜 필요한가</b> — 전에는 {@code SPRING_PROFILES_ACTIVE} 가 비면 그대로 부트스트랩 체인이 붙었다: permitAll · CSRF
 * off · 전 요청이 {@code app_user.id=1}. 그런데 <b>부팅은 성공</b>하고 남는 건 WARN 한 줄이라, 화면은 정상으로 보인다. 반대로 허용 이메일이
 * 비면(= 아무도 못 들어와 피해가 없는 상태) 부팅을 막았다. 두 오설정에 걸린 기준이 정확히 반대였다 — <b>아무나 들어오는 쪽</b>이 통과하고 <b>아무도 못 들어오는
 * 쪽</b>이 차단됐다.
 *
 * <p>그래서 조건을 "프로필 없음"이 아니라 <b>"프로필 없음 + 명시적 opt-in 없음"</b>으로 잡는다. 로컬 부트스트랩과 배포 안전이 양립한다.
 *
 * <p><b>opt-in 값을 어디에 두는지가 이 장치의 전부다.</b> {@code .env.example} 에는 <b>넣지 않는다</b> — 그 파일은 복사해서 배포하는
 * 템플릿이라, 거기 적으면 무인증이 배포까지 따라오고 플래그 이름만 바뀐 같은 구멍이 된다. 대신 개발 실행 경로에만 준다:
 *
 * <ul>
 *   <li>{@code ./gradlew bootRun} · {@code ./gradlew test} — build.gradle 이 env 로 주입한다
 *   <li>배포 산출물({@code bootJar})은 그 경로를 거치지 않으므로 아무것도 물려받지 않는다
 * </ul>
 *
 * <p>{@link InitializingBean} 인 이유: 부팅을 <b>실제로</b> 막아야 한다. {@code ApplicationRunner} 는 웹 서버가 포트를 연
 * <b>뒤에</b> 실행되므로 "부팅 실패"가 아니라 "부팅 후 종료"가 되고, 그 사이 요청을 받는다.
 */
@Component
@Profile("!oauth")
class BootstrapModeGuard implements InitializingBean {

    /** 환경변수 {@code RECALL_BOOTSTRAP_MODE} (Spring relaxed binding). */
    private final boolean optedIn;

    BootstrapModeGuard(@Value("${recall.bootstrap-mode:false}") boolean optedIn) {
        this.optedIn = optedIn;
    }

    @Override
    public void afterPropertiesSet() {
        if (optedIn) {
            return;
        }
        throw new IllegalStateException(
                """
                인증이 배선되지 않았는데 부트스트랩 모드 opt-in 도 없다 — 부팅을 막는다.
                이 상태로 뜨면 전 요청이 인증 없이 app_user.id=1 로 스코프된다(열린 인스턴스).

                  배포/운영  : SPRING_PROFILES_ACTIVE=oauth 로 로그인을 켠다
                               (GOOGLE_OAUTH_CLIENT_ID·GOOGLE_OAUTH_CLIENT_SECRET·RECALL_ALLOWED_EMAILS 필요)
                  로컬 확인  : RECALL_BOOTSTRAP_MODE=true 를 의도적으로 준다
                               (./gradlew bootRun · ./gradlew test 는 이미 주입한다)
                """);
    }
}
