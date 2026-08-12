package com.recall.settings;

import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.SettingsService.SettingsUpdate;
import com.recall.settings.dto.CatalogResponse;
import com.recall.settings.dto.ModelSettingsRequest;
import com.recall.settings.dto.ModelSettingsResponse;
import com.recall.settings.dto.ModelSettingsResponse.ChatSlot;
import com.recall.settings.dto.ModelSettingsResponse.EmbeddingSlot;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모델(chat/embedding) 설정 입구. HTTP 변환만 담당하고 검증·프로브·영속은 {@link SettingsService}가 맡는다.
 *
 * <p>🔴 시큐어코딩: 어떤 응답도 API 키 평문을 담지 않는다({@code apiKeyConfigured} 불리언만).
 */
@RestController
@RequestMapping("/api/settings/models")
public class SettingsController {

    private final SettingsService settingsService;
    private final ProviderCatalog catalog;

    public SettingsController(SettingsService settingsService, ProviderCatalog catalog) {
        this.settingsService = settingsService;
        this.catalog = catalog;
    }

    /** 현재 모델 설정 조회. */
    @GetMapping
    public ModelSettingsResponse get() {
        return currentResponse();
    }

    /** 모델 설정 변경 — 검증(capability)·프로브(embedding)는 서비스가 수행, 실패는 전역 핸들러가 400으로 변환. */
    @PutMapping
    public ModelSettingsResponse update(@Valid @RequestBody ModelSettingsRequest request) {
        settingsService.update(toSettingsUpdate(request));
        return currentResponse();
    }

    /** 역할별 허용 provider·모델 카탈로그(UI 드롭다운용). */
    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return new CatalogResponse(catalog.chatModels(), catalog.embeddingModels());
    }

    private ModelSettingsResponse currentResponse() {
        LlmProperties chat = settingsService.currentChat();
        EmbeddingProperties embedding = settingsService.currentEmbedding();
        String status = settingsService.embeddingStatus();
        return new ModelSettingsResponse(
                new ChatSlot(chat.provider(), chat.model(), notBlank(chat.apiKey())),
                new EmbeddingSlot(
                        embedding.provider(),
                        embedding.model(),
                        notBlank(embedding.apiKey()),
                        status));
    }

    private static SettingsUpdate toSettingsUpdate(ModelSettingsRequest request) {
        ModelSettingsRequest.ChatReq chat = request.chat();
        ModelSettingsRequest.EmbeddingReq embedding = request.embedding();
        return new SettingsUpdate(
                chat == null ? null : chat.provider(),
                chat == null ? null : chat.model(),
                chat == null ? null : chat.apiKey(),
                embedding == null ? null : embedding.provider(),
                embedding == null ? null : embedding.model(),
                embedding == null ? null : embedding.apiKey());
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
