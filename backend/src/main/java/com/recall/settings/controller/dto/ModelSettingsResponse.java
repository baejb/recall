package com.recall.settings.controller.dto;

/**
 * 현재 모델 설정 조회 응답. 키는 절대 평문으로 담지 않는다 — {@code apiKeyConfigured} 불리언만(시큐어코딩, backend/CLAUDE.md).
 * base-url 은 비밀이 아니므로 그대로 노출한다.
 *
 * @param chat 채팅(생성) 슬롯
 * @param embedding 임베딩 슬롯(재색인 상태 포함)
 */
public record ModelSettingsResponse(ChatSlot chat, EmbeddingSlot embedding) {

    public record ChatSlot(
            String provider, String model, boolean apiKeyConfigured, String baseUrl) {}

    public record EmbeddingSlot(
            String provider,
            String model,
            boolean apiKeyConfigured,
            String baseUrl,
            String status) {}
}
