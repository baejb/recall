package com.recall.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 🔴 인증이 배선되지 않은 채로 부팅되지 않는지 고정한다.
 *
 * <p>전에는 {@code SPRING_PROFILES_ACTIVE} 가 비면 그대로 부트스트랩 체인이 붙었다: permitAll · CSRF off · 전 요청이 {@code
 * app_user.id=1}. 그런데 <b>부팅은 성공</b>하고 남는 건 WARN 한 줄이라 화면은 정상으로 보인다. 반대로 허용 이메일이 비면(= 아무도 못 들어와 피해가
 * 없는 상태) 부팅을 막았다 — 두 오설정에 걸린 기준이 정확히 반대였다.
 *
 * <p>이 테스트가 지키는 것은 "opt-in 이 없으면 던진다"까지다. opt-in 값을 <b>어디에 두는지</b>는 테스트로 지킬 수 없다 — build.gradle 의
 * bootRun·test 에만 준다({@code .env.example} 에 두면 복사해서 배포할 때 무인증이 따라오고 플래그 이름만 바뀐 같은 구멍이 된다).
 */
@Tag("release-gate")
class BootstrapModeGuardTest {

    @Test
    @DisplayName("🔴 opt-in 이 없으면 부팅을 막는다 — 인증 없이 뜨는 인스턴스가 조용히 생기지 않는다")
    void refusesToStartWithoutExplicitOptIn() {
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> new BootstrapModeGuard(false).afterPropertiesSet());

        // 메시지가 원인과 해결책을 함께 담아야 한다 — 부팅 실패만 보이면 무엇을 설정해야 하는지 알 수 없다.
        assertTrue(
                thrown.getMessage().contains("SPRING_PROFILES_ACTIVE=oauth"),
                () -> "로그인을 켜는 방법이 메시지에 없다: " + thrown.getMessage());
        assertTrue(
                thrown.getMessage().contains("RECALL_BOOTSTRAP_MODE=true"),
                () -> "의도적 opt-in 방법이 메시지에 없다: " + thrown.getMessage());
    }

    @Test
    @DisplayName("의도적으로 opt-in 하면 통과한다 — 로컬 개발·테스트가 막히지 않는다")
    void allowsExplicitOptIn() {
        assertDoesNotThrow(() -> new BootstrapModeGuard(true).afterPropertiesSet());
    }
}
