package com.recall.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.BootstrapCurrentUserProvider;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ModelSettingInitializerTest {

    @Autowired JdbcTemplate jdbc;

    /** V4 시드 상태(anthropic/voyage, configured=false)를 흉내낸 실제 엔티티. */
    private ModelSetting seedRow() {
        ModelSetting s = new ModelSetting();
        s.setChatProvider("anthropic");
        s.setChatModel("claude-opus-4-8");
        s.setEmbeddingProvider("voyage");
        s.setEmbeddingStatus("READY");
        s.setConfigured(false);
        return s;
    }

    @Test
    @DisplayName("initializer는 부트스트랩(1) 행만 시드 — 다른 사용자 행을 만들지 않는다")
    void seedsBootstrapOnly() {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM model_setting", Integer.class);
        assertEquals(1, rows, "부팅 후 model_setting은 부트스트랩 1행뿐");
        assertEquals(
                1L, (long) jdbc.queryForObject("SELECT user_id FROM model_setting", Long.class));
    }

    @Test
    void seedsProviderModelAndBaseUrlFromEnvOnFirstBoot() {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting row = seedRow();
        row.setChatApiKeyEnc("enc-chat"); // 키 컬럼은 시더가 건드리면 안 된다
        row.setEmbeddingApiKeyEnc("enc-embedding");
        when(repo.findByUserId(BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID))
                .thenReturn(Optional.of(row));

        ModelSettingInitializer init =
                new ModelSettingInitializer(
                        repo,
                        new LlmProperties("openai", "", "gpt-4.1", "https://proxy.example", 4096),
                        new EmbeddingProperties(
                                "openai",
                                "",
                                "text-embedding-3-small",
                                "https://emb.example",
                                1024));

        init.seedFromEnvIfNeeded();

        assertEquals("openai", row.getChatProvider());
        assertEquals("gpt-4.1", row.getChatModel());
        assertEquals("https://proxy.example", row.getChatBaseUrl());
        assertEquals("openai", row.getEmbeddingProvider());
        assertEquals("text-embedding-3-small", row.getEmbeddingModel());
        assertEquals("https://emb.example", row.getEmbeddingBaseUrl());
        assertTrue(row.isConfigured(), "시드 후 configured=true 로 잠겨야 한다");
        // 키 컬럼은 절대 건드리지 않는다(키는 env 에 남는다)
        assertEquals("enc-chat", row.getChatApiKeyEnc());
        assertEquals("enc-embedding", row.getEmbeddingApiKeyEnc());
        verify(repo).save(row);
    }

    @Test
    void doesNothingWhenAlreadyConfigured() {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        ModelSetting row = seedRow();
        row.setChatProvider("google"); // UI 로 이미 편집된 값
        row.setConfigured(true);
        when(repo.findByUserId(BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID))
                .thenReturn(Optional.of(row));

        ModelSettingInitializer init =
                new ModelSettingInitializer(
                        repo,
                        new LlmProperties("openai", "", "gpt-4.1", "https://proxy.example", 4096),
                        new EmbeddingProperties(
                                "openai", "", "emb-x", "https://emb.example", 1024));

        init.seedFromEnvIfNeeded();

        assertEquals("google", row.getChatProvider(), "이미 configured 면 env 로 덮어쓰지 않는다");
        verify(repo, never()).save(row);
    }
}
