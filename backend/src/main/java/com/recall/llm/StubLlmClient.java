package com.recall.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Phase 0 walking skeleton용 LLM stub. 실제 provider 구현이 없을 때만 활성화된다 ({@link
 * ConditionalOnMissingBean}). 흐름이 끝까지 돌게 placeholder를 반환하되, stub이 관여했음을 로그로 남긴다(조용한 실패 금지).
 */
@Component
@ConditionalOnMissingBean(LlmClient.class)
public class StubLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(StubLlmClient.class);

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        log.warn("[STUB] LlmClient.complete 호출 — 실제 LLM 미연동, placeholder 반환");
        return "[stub-llm-response]";
    }
}
