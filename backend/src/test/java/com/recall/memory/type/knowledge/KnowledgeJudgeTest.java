package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.recall.common.PromptLoader;
import com.recall.llm.LlmClient;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.Verdict;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeJudgeTest {

    private static final Map<String, Object> PROPOSED = Map.of("title", "RRF", "document", "본문");
    private static final Map<String, Object> EXISTING = Map.of("title", "RRF", "document", "기존 본문");

    private KnowledgeJudge withResponse(String response) {
        LlmClient fake = (system, user) -> response;
        return new KnowledgeJudge(fake, new PromptLoader());
    }

    @Test
    @DisplayName("supports()는 KNOWLEDGE")
    void supports() {
        assertEquals(Verdict.NEW, withResponse("{}").judge(PROPOSED, Map.of()).verdict());
    }

    @Test
    @DisplayName("유사 후보가 없으면(existing 빈 맵) LLM 없이 NEW")
    void newWhenNoExisting() {
        Judgement j = withResponse("{\"verdict\":\"RECURRENCE\"}").judge(PROPOSED, Map.of());
        assertEquals(Verdict.NEW, j.verdict());
        assertNull(j.targetMemoryId());
    }

    @Test
    @DisplayName("정상 JSON 판정을 verdict·rationale로 매핑")
    void mapsValidJudgement() {
        Judgement j =
                withResponse("{\"verdict\":\"RECURRENCE\",\"rationale\":\"같은 문제\"}")
                        .judge(PROPOSED, EXISTING);
        assertEquals(Verdict.RECURRENCE, j.verdict());
        assertEquals("같은 문제", j.rationale());
        assertNull(j.targetMemoryId()); // 파이프라인이 채움
    }

    @Test
    @DisplayName("산문에 감싸인 JSON도 판정으로 추출")
    void extractsJsonFromProse() {
        Judgement j =
                withResponse("판정 결과:\n{\"verdict\":\"CONFLICT\",\"rationale\":\"모순\"}\n이상")
                        .judge(PROPOSED, EXISTING);
        assertEquals(Verdict.CONFLICT, j.verdict());
    }

    @Test
    @DisplayName("stub/깨진 응답이면 fallback(SUPPLEMENT — 사람 검토 유도)")
    void fallbackOnUnparseable() {
        Judgement j = withResponse("[stub-llm-response]").judge(PROPOSED, EXISTING);
        assertEquals(Verdict.SUPPLEMENT, j.verdict());
    }

    @Test
    @DisplayName("알 수 없는 verdict 문자열도 fallback")
    void fallbackOnUnknownVerdict() {
        Judgement j =
                withResponse("{\"verdict\":\"MAYBE\",\"rationale\":\"x\"}")
                        .judge(PROPOSED, EXISTING);
        assertEquals(Verdict.SUPPLEMENT, j.verdict());
    }
}
