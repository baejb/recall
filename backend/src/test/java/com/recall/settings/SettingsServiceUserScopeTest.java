package com.recall.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * env 키 폴백이 부트스트랩 사용자에게만 적용되는지 검증한다(스펙 §7) — model_setting 행이 없는 다른 사용자에게 env 키가 새면 🔴 치명(자격증명 유출과
 * 동급의 스코프 붕괴)이므로 회귀로 고정한다.
 *
 * <p>사용자2는 일부러 model_setting 행을 만들지 않는다 — 막 가입해 설정을 한 번도 만진 적 없는 사용자가 실제로 이 상태다. {@code
 * isChatConfigured}/{@code isEmbeddingConfigured}는 이 경우도 예외 없이 미설정(false)으로 답해야 한다.
 */
@SpringBootTest
class SettingsServiceUserScopeTest {

    @Autowired SettingsService settings;
    @Autowired JdbcTemplate jdbc;

    long user2;

    @BeforeEach
    void seed() {
        user2 =
                jdbc.queryForObject(
                        "INSERT INTO app_user(provider,subject) VALUES('test','s2') RETURNING id",
                        Long.class);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM model_setting WHERE user_id=?", user2);
        jdbc.update("DELETE FROM app_user WHERE id=?", user2);
    }

    @Test
    @DisplayName("env 폴백은 부트스트랩(1)만 — 미설정 사용자2는 chat/embedding 미설정")
    void envFallbackBootstrapOnly() {
        assertFalse(settings.isChatConfigured(user2), "사용자2는 env 키가 있어도 미설정");
        assertFalse(settings.isEmbeddingConfigured(user2));
        // 부트스트랩은 env 시드로 최소 embedding 설정될 수 있으나(로컬/CI env 값 유무에 따라
        // 달라짐), 이 테스트는 "다른 사용자에게 env 키가 새지 않는다"만 고정한다.
    }
}
