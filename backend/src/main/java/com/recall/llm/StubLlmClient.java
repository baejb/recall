package com.recall.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 0 walking skeleton용 LLM stub. 실제 provider 어댑터가 없을 때 {@link LlmConfig}가 기본 빈으로 등록한다. 흐름이 끝까지
 * 돌게 placeholder를 반환하되, stub이 관여했음을 로그로 남긴다(조용한 실패 금지).
 */
public class StubLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(StubLlmClient.class);

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        log.warn("[STUB] LlmClient.complete 호출 — 실제 LLM 미연동, placeholder 반환");
        return "[stub-llm-response]";
    }
}
