package com.recall.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    private static final String SECRET_KEY = "sk-secret-123";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SettingsService settingsService;

    @Test
    void getReturnsApiKeyConfiguredButNeverRawKey() throws Exception {
        when(settingsService.currentChat())
                .thenReturn(
                        new LlmProperties("anthropic", SECRET_KEY, "claude-opus-4-8", null, 4096));
        when(settingsService.currentEmbedding())
                .thenReturn(new EmbeddingProperties("voyage", SECRET_KEY, "voyage-3", null, 1024));
        when(settingsService.embeddingStatus()).thenReturn("READY");

        String body =
                mockMvc.perform(get("/api/settings/models"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.chat.provider").value("anthropic"))
                        .andExpect(jsonPath("$.chat.apiKeyConfigured").value(true))
                        .andExpect(jsonPath("$.embedding.provider").value("voyage"))
                        .andExpect(jsonPath("$.embedding.apiKeyConfigured").value(true))
                        .andExpect(jsonPath("$.embedding.status").value("READY"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertFalse(body.contains(SECRET_KEY), "응답에 API 키 평문이 노출되면 안 된다");
    }

    @Test
    void getReflectsMissingKeyAsFalse() throws Exception {
        when(settingsService.currentChat())
                .thenReturn(new LlmProperties("anthropic", "", "claude-opus-4-8", null, 4096));
        when(settingsService.currentEmbedding())
                .thenReturn(new EmbeddingProperties("voyage", "", "voyage-3", null, 1024));
        when(settingsService.embeddingStatus()).thenReturn("READY");

        mockMvc.perform(get("/api/settings/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.embedding.apiKeyConfigured").value(false));
    }

    @Test
    void putRejectsUnsupportedCapabilityWith400() throws Exception {
        when(settingsService.update(any()))
                .thenThrow(
                        new IllegalArgumentException("EMBEDDING 역할이 지원하지 않는 provider: anthropic"));

        String requestBody =
                """
                { "embedding": {"provider": "anthropic", "model": "x", "apiKey": null} }
                """;

        mockMvc.perform(
                        put("/api/settings/models")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putSurfacesProbeFailureAs400() throws Exception {
        when(settingsService.update(any()))
                .thenThrow(new EmbeddingProbeException("임베딩 설정 검증 실패(키/모델 확인): 401 unauthorized"));

        String requestBody =
                """
                { "embedding": {"provider": "openai", "model": "text-embedding-3-small", "apiKey": "sk-new"} }
                """;

        mockMvc.perform(
                        put("/api/settings/models")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putSucceedsAndReturnsUpdatedSettings() throws Exception {
        when(settingsService.update(any())).thenReturn(new SettingsService.UpdateResult(false));
        when(settingsService.currentChat())
                .thenReturn(new LlmProperties("openai", SECRET_KEY, "gpt-4.1", null, 4096));
        when(settingsService.currentEmbedding())
                .thenReturn(new EmbeddingProperties("voyage", SECRET_KEY, "voyage-3", null, 1024));
        when(settingsService.embeddingStatus()).thenReturn("READY");

        String requestBody =
                """
                { "chat": {"provider": "openai", "model": "gpt-4.1", "apiKey": "sk-new"} }
                """;

        mockMvc.perform(
                        put("/api/settings/models")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat.provider").value("openai"))
                .andExpect(jsonPath("$.chat.apiKeyConfigured").value(true));
    }

    @Test
    void catalogShowsCapabilityAsymmetry() throws Exception {
        mockMvc.perform(get("/api/settings/models/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatModels.anthropic").exists())
                .andExpect(jsonPath("$.chatModels.voyage").doesNotExist())
                .andExpect(jsonPath("$.embeddingModels.voyage").exists())
                .andExpect(jsonPath("$.embeddingModels.anthropic").doesNotExist());
    }
}
