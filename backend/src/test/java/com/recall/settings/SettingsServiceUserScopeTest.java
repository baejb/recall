package com.recall.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.common.config.BootstrapCurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * env 키 폴백이 부트스트랩 사용자에게만 적용되는지 검증한다(스펙 §7) — model_setting 행이 없는 다른 사용자에게 env 키가 새면 🔴 치명(자격증명 유출과
 * 동급의 스코프 붕괴)이므로 회귀로 고정한다.
 *
 * <p>{@link DynamicPropertySource}로 env 키를 **가짜 non-blank 값**으로 강제 주입한다 — ambient 환경(로컬 .env, CI)에
 * 실제 키가 있는지 없는지와 무관하게 항상 실효 검증되도록 하기 위함이다. 주입 없이 로컬 환경 변수에만 의존하면, env 키가 비어 있는 환경에서는 부트스트랩 전용 게이트를
 * 통째로 제거해도 두 분기 모두 "미설정"이라 테스트가 그냥 통과해 버려 경계 회귀를 잡지 못한다.
 *
 * <p>사용자2는 일부러 model_setting 행을 만들지 않는다 — 막 가입해 설정을 한 번도 만진 적 없는 사용자가 실제로 이 상태다. {@code
 * isChatConfigured}/{@code isEmbeddingConfigured}는 이 경우도 예외 없이 미설정(false)으로 답해야 한다.
 */
@Tag("release-gate")
@SpringBootTest(
        properties = {
            "recall.llm.api-key=test-env-chat-key",
            "recall.llm.embedding.api-key=test-env-emb-key"
        })
class SettingsServiceUserScopeTest {

    @Autowired SettingsService settings;
    @Autowired JdbcTemplate jdbc;

    long user2;

    @DynamicPropertySource
    static void fakeEnvKeys(DynamicPropertyRegistry registry) {
        // @SpringBootTest(properties=...) 만으로 충분하지만, 다른 프로퍼티 소스(.env 파생 환경변수 등)가
        // 더 높은 우선순위를 가질 가능성을 차단하기 위해 동적 프로퍼티로도 동일 값을 강제한다.
        registry.add("recall.llm.api-key", () -> "test-env-chat-key");
        registry.add("recall.llm.embedding.api-key", () -> "test-env-emb-key");
    }

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
    @DisplayName("env 폴백은 부트스트랩(1)만 — 사용자2는 env 키가 있어도 chat/embedding 미설정")
    void envFallbackBootstrapOnly() {
        // 부트스트랩 사용자는 DB 키가 없어도 env 폴백으로 설정됨 취급 — 폴백 경로 자체가 살아있는지 확인.
        assertTrue(
                settings.isChatConfigured(BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID),
                "부트스트랩은 env 키로 chat 설정됨");
        assertTrue(
                settings.isEmbeddingConfigured(BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID),
                "부트스트랩은 env 키로 embedding 설정됨");

        // 사용자2는 같은 env 키가 존재해도 도달하면 안 된다 — 이 분기가 깨지면(게이트 제거) 위 부트스트랩
        // 단정은 여전히 통과하지만 아래 단정이 반드시 실패해 회귀를 잡는다.
        assertFalse(settings.isChatConfigured(user2), "사용자2는 env 키가 있어도 미설정");
        assertFalse(settings.isEmbeddingConfigured(user2), "사용자2는 env 키가 있어도 미설정");
    }
}
