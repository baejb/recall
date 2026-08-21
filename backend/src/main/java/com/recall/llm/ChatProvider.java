package com.recall.llm;

import java.util.List;

/**
 * chat(LLM) provider 서술자(SPI). 각 provider 구현이 자신의 이름·추천 모델·클라이언트 생성법을 스스로 밝힌다(자가 등록). provider 가용성은
 * 이 서술자 빈의 등록 여부로만 결정되므로 카탈로그↔팩토리 드리프트(한쪽엔 있고 한쪽엔 없는 provider)가 구조적으로 사라진다.
 *
 * <p>{@link com.recall.common.TypeStrategy}/{@link com.recall.common.StrategyRegistry} 자가 등록 패턴을
 * chat/embedding provider 축에 그대로 적용한 것.
 */
public interface ChatProvider {

    /** provider 식별자(소문자, 예: "anthropic"). */
    String name();

    /** UI 드롭다운용 추천 모델(불변). 사용자는 이 목록 밖 모델명도 지정할 수 있다 — 검증은 provider 등록 여부만 본다. */
    List<String> recommendedModels();

    /** 설정으로 실제 LLM 클라이언트를 만든다. */
    LlmClient create(LlmProperties props);
}
