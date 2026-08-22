package com.recall.settings.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 역할별 허용 provider·모델 정적 카탈로그(UI 드롭다운용). 맵의 키가 그 역할이 지원하는 provider 집합이다 — capability 비대칭이 그대로
 * 드러난다(예: embeddingModels 에는 anthropic 이 없다).
 */
public record CatalogResponse(
        Map<String, List<String>> chatModels, Map<String, List<String>> embeddingModels) {}
