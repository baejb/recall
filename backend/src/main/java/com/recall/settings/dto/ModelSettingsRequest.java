package com.recall.settings.dto;

/**
 * 모델 설정 변경 요청. chat/embedding 각각 생략(null) 가능 — 생략 시 해당 슬롯은 변경하지 않는다. apiKey 는 비어 있으면(null/blank) 기존
 * 값을 유지한다({@link com.recall.settings.SettingsService#update} 참고).
 */
public record ModelSettingsRequest(ChatReq chat, EmbeddingReq embedding) {

    public record ChatReq(String provider, String model, String apiKey) {}

    public record EmbeddingReq(String provider, String model, String apiKey) {}
}
