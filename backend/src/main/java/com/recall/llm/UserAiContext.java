package com.recall.llm;

import com.recall.common.exception.AiNotConfiguredException;

/**
 * 사용자별 AI 클라이언트 스냅샷 — {@link AiContextFactory#forUser(long)} 호출 시점의 {@code SettingsService} 조회로 고정한
 * chat/embedding 클라이언트와 각각의 준비 여부(chatReady/embeddingReady)를 담는다.
 *
 * <p>{@code forUser}는 미설정을 이유로 예외를 던지지 않는다 — 준비 안 됐으면 chatReady/embeddingReady 가 false 인 컨텍스트를 그대로
 * 돌려준다. 실제 차단(409)은 사용 지점에서 {@link #requireChat()}/{@link #requireEmbedding()}을 호출할 때 일어난다.
 *
 * <p>{@link #toString()}은 진단 로그용으로 userId/chatReady/embeddingReady 만 노출한다 — llm/embedding 클라이언트
 * 내부(키·provider 등)는 절대 담지 않는다(backend/CLAUDE.md: 키 값을 로그·예외·toString 에 넣지 않는다).
 */
public record UserAiContext(
        long userId,
        LlmClient llm,
        EmbeddingClient embedding,
        boolean chatReady,
        boolean embeddingReady) {

    /** chat 이 설정돼 있으면 바인딩된 {@link LlmClient} 를 반환하고, 아니면 409 로 변환되는 예외를 던진다. */
    public LlmClient requireChat() {
        if (!chatReady) {
            throw new AiNotConfiguredException("chat 모델이 설정되지 않았습니다(user=" + userId + ")");
        }
        return llm;
    }

    /** embedding 이 설정돼 있으면 바인딩된 {@link EmbeddingClient} 를 반환하고, 아니면 409 로 변환되는 예외를 던진다. */
    public EmbeddingClient requireEmbedding() {
        if (!embeddingReady) {
            throw new AiNotConfiguredException("embedding 모델이 설정되지 않았습니다(user=" + userId + ")");
        }
        return embedding;
    }

    /** 키·provider 등 민감/내부 상태 없이 진단에 필요한 최소 정보만 노출한다. */
    @Override
    public String toString() {
        return "UserAiContext[userId="
                + userId
                + ", chatReady="
                + chatReady
                + ", embeddingReady="
                + embeddingReady
                + "]";
    }
}
