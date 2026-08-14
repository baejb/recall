package com.recall.llm;

import java.util.function.Consumer;

/**
 * 외부 LLM 호출 포트(BYO key). 확률적 단계(분류 C·리랭크 RR·답변 A·추출 S2·판정 S4)만 이 포트에 의존한다. 결정론 단계(M0·P·R·W)는 이 포트를
 * 쓰지 않는다(architecture.md — 포트는 교체가 실제로 필요한 경계에만).
 *
 * <p>provider 교체·강/저가 모델 분리·테스트용 fake 주입을 위해 인터페이스로 연다.
 */
public interface LlmClient {

    /**
     * 시스템/사용자 프롬프트로 completion을 받는다.
     *
     * @return 모델 응답 텍스트
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * completion을 부분 텍스트(토큰) 단위로 스트리밍한다. {@code onToken}은 도착 순서대로 부분 문자열을 받는다.
     *
     * <p>기본 구현은 스트리밍을 지원하지 않는 provider를 위해 {@link #complete}를 호출해 결과를 한 번에 흘려보낸다(격하 — 답변이 통째로 도착).
     * provider가 스트리밍 API를 가지면 이 메서드를 오버라이드한다.
     */
    default void completeStream(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        onToken.accept(complete(systemPrompt, userPrompt));
    }

    /**
     * 실제 LLM(비-stub)이 연동되어 있는지. 답변합성(A) 등 확률적 단계가 이 값으로 격하 여부를 판단한다(false면 stub이 관여하므로 placeholder를
     * 답으로 쓰지 않고 결정론 폴백으로 격하 — 근거 없는 생성 금지).
     */
    default boolean available() {
        return true;
    }
}
