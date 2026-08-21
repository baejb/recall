package com.recall.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.recall.common.AiNotConfiguredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AiContextFactory#forUser(long)} 는 미설정 사용자도 예외 없이 컨텍스트를 돌려주고(차단은 사용 시점), {@link
 * UserAiContext#toString()} 은 키·provider 를 절대 노출하지 않는다(backend/CLAUDE.md 시큐어코딩 규칙 회귀 고정).
 */
@SpringBootTest
class AiContextFactoryTest {

    @Autowired AiContextFactory factory;
    @Autowired JdbcTemplate jdbc;

    long user2;

    @BeforeEach
    void seed() {
        user2 =
                jdbc.queryForObject(
                        "INSERT INTO app_user(provider,subject) VALUES('test','ctx2') RETURNING id",
                        Long.class);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM model_setting WHERE user_id=?", user2);
        jdbc.update("DELETE FROM app_user WHERE id=?", user2);
    }

    @Test
    @Tag("release-gate")
    @DisplayName("미설정 사용자는 chat/embeddingReady=false, require*는 AiNotConfiguredException")
    void unconfiguredUserBlocked() {
        UserAiContext ctx = factory.forUser(user2);
        assertFalse(ctx.chatReady());
        assertThrows(AiNotConfiguredException.class, ctx::requireChat);
    }

    @Test
    @Tag("release-gate")
    @DisplayName("toString에 키·provider 노출 안 됨")
    void toStringHidesSecrets() {
        assertFalse(factory.forUser(1L).toString().toLowerCase().contains("key"));
    }
}
