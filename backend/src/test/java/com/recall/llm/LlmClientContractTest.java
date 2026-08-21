package com.recall.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** LlmClient 기본 계약 — 스트리밍 미지원 구현의 격하(complete 한 번에) + 가용성 기본값 + stub 판정. */
class LlmClientContractTest {

    @Test
    @DisplayName("기본 completeStream은 complete 결과를 한 번에 흘리고, available 기본값은 true")
    void defaultStreamFallsBackToComplete() {
        LlmClient onlyComplete = (system, user) -> "전체 답변";

        List<String> tokens = new ArrayList<>();
        onlyComplete.completeStream("s", "u", tokens::add);

        assertEquals(List.of("전체 답변"), tokens);
        assertTrue(onlyComplete.available());
    }

    @Test
    @DisplayName("StubLlmClient는 available=false — 격하 판정의 기준")
    void stubIsNotAvailable() {
        assertFalse(new StubLlmClient().available());
    }
}
