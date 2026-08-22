package com.recall.settings.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recall.common.exception.ValidationException;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.google.GoogleChatProvider;
import com.recall.llm.provider.google.GoogleEmbeddingProvider;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import com.recall.llm.provider.openai.OpenAiEmbeddingProvider;
import com.recall.llm.provider.voyage.VoyageEmbeddingProvider;
import com.recall.settings.service.EmbeddingProbeException;
import com.recall.settings.service.ProviderCatalog;
import com.recall.settings.service.SettingsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettingsController.class)
@Import(SettingsControllerTest.CatalogConfig.class)
class SettingsControllerTest {

    private static final String SECRET_KEY = "sk-secret-123";

    /** 카탈로그를 실제 서술자에서 파생시켜 slice 에 제공한다 — 응답 비대칭은 하드코딩이 아니라 등록 서술자에서 나온다. */
    @TestConfiguration
    static class CatalogConfig {
        @Bean
        ProviderCatalog providerCatalog() {
            return new ProviderCatalog(
                    List.of(
                            new AnthropicChatProvider(),
                            new OpenAiChatProvider(),
                            new GoogleChatProvider()),
                    List.of(
                            new OpenAiEmbeddingProvider(),
                            new VoyageEmbeddingProvider(),
                            new GoogleEmbeddingProvider()));
        }
    }

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
                        .andExpect(jsonPath("$.data.chat.provider").value("anthropic"))
                        .andExpect(jsonPath("$.data.chat.apiKeyConfigured").value(true))
                        .andExpect(jsonPath("$.data.embedding.provider").value("voyage"))
                        .andExpect(jsonPath("$.data.embedding.apiKeyConfigured").value(true))
                        .andExpect(jsonPath("$.data.embedding.status").value("READY"))
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
                .andExpect(jsonPath("$.data.chat.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.data.embedding.apiKeyConfigured").value(false));
    }

    @Test
    void putRejectsUnsupportedCapabilityWith400() throws Exception {
        // 예외 타입으로 스텁한다 — 전에는 IllegalArgumentException 을 던지고 400 을 기대했는데, 그건
        // 전역 핸들러가 "모든 IllegalArgumentException = 400" 이라는 너무 넓은 규칙을 갖고 있어서였다.
        // 그 규칙은 내부 배선 버그(등록된 전략 없음)까지 400 으로 감추므로 지웠고, 검증 실패는 타입으로 말한다.
        when(settingsService.update(any()))
                .thenThrow(
                        new ValidationException(
                                "EMBEDDING 역할이 지원하지 않는 provider: anthropic", "provider"));

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
                .thenThrow(
                        new EmbeddingProbeException(
                                "임베딩 설정 검증 실패(키·모델·base URL 확인): 401 unauthorized"));

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
                .andExpect(jsonPath("$.data.chat.provider").value("openai"))
                .andExpect(jsonPath("$.data.chat.apiKeyConfigured").value(true));
    }

    @Test
    void getExposesBaseUrlButNeverRawKey() throws Exception {
        when(settingsService.currentChat())
                .thenReturn(
                        new LlmProperties(
                                "openai", SECRET_KEY, "gpt-4.1", "https://proxy.example", 4096));
        when(settingsService.currentEmbedding())
                .thenReturn(
                        new EmbeddingProperties(
                                "openai",
                                SECRET_KEY,
                                "text-embedding-3-small",
                                "https://emb.example",
                                1024));
        when(settingsService.embeddingStatus()).thenReturn("READY");

        String body =
                mockMvc.perform(get("/api/settings/models"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.chat.baseUrl").value("https://proxy.example"))
                        .andExpect(
                                jsonPath("$.data.embedding.baseUrl").value("https://emb.example"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertFalse(body.contains(SECRET_KEY), "base-url 을 노출해도 API 키 평문은 담기지 않는다");
    }

    @Test
    void putThreadsBaseUrlIntoUpdate() throws Exception {
        when(settingsService.update(any())).thenReturn(new SettingsService.UpdateResult(false));
        when(settingsService.currentChat())
                .thenReturn(
                        new LlmProperties(
                                "openai", SECRET_KEY, "gpt-4.1", "https://proxy.example", 4096));
        when(settingsService.currentEmbedding())
                .thenReturn(new EmbeddingProperties("voyage", SECRET_KEY, "voyage-3", null, 1024));
        when(settingsService.embeddingStatus()).thenReturn("READY");

        String requestBody =
                """
                { "chat": {"provider": "openai", "model": "gpt-4.1", "apiKey": null, "baseUrl": "https://proxy.example"} }
                """;

        mockMvc.perform(
                        put("/api/settings/models")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chat.baseUrl").value("https://proxy.example"));

        ArgumentCaptor<SettingsService.SettingsUpdate> captor =
                ArgumentCaptor.forClass(SettingsService.SettingsUpdate.class);
        verify(settingsService).update(captor.capture());
        assertEquals("https://proxy.example", captor.getValue().chatBaseUrl());
    }

    @Test
    void catalogShowsCapabilityAsymmetry() throws Exception {
        mockMvc.perform(get("/api/settings/models/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatModels.anthropic").exists())
                .andExpect(jsonPath("$.data.chatModels.voyage").doesNotExist())
                .andExpect(jsonPath("$.data.embeddingModels.voyage").exists())
                .andExpect(jsonPath("$.data.embeddingModels.anthropic").doesNotExist());
    }
}
