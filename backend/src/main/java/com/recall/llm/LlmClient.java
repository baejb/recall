package com.recall.llm;

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
}
