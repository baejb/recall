package com.recall.store.service;

import com.recall.common.prompt.PromptLoader;
import com.recall.common.type.MemoryType;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.ExtractionStrategy;
import com.recall.memory.type.knowledge.KnowledgeExtraction;
import com.recall.memory.type.troubleshooting.TroubleshootingExtraction;
import com.recall.testsupport.EvalCases;
import com.recall.testsupport.LlmEvalSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 🔵 C(유형 라우팅) Eval — 저장 경로가 원문을 옳은 유형으로 보내는지 라벨셋으로 채점한다. 라벨셋은 {@code eval/type-routing-c.json}.
 *
 * <p>PRD §05 의 {@code domain ≥ 0.88} 을 임계로 쓴다. 케이스는 두 방향을 섞었다: 에러를 <b>겪은</b> 기록(TROUBLESHOOTING)과
 * 에러를 <b>설명 소재로 언급한</b> 지식(KNOWLEDGE). 후자는 실제로 틀리기 쉬운 쪽이라 일부러 넣었다 — "NullPointerException 은 정확 토큰
 * 매칭이 잘 먹는다"는 문장은 에러 이름이 있지만 겪은 사건이 아니다.
 *
 * <p>실제 provider 를 호출하므로 {@code ./gradlew llmEval} 로만 돈다(기본 {@code test}·CI 에서 제외). 키가 없으면 스킵된다.
 */
@Tag("llm-eval")
class TypeRoutingEvalTest {

    /** PRD §05 domain 축 임계. */
    private static final double THRESHOLD = 0.88;

    @Test
    @DisplayName("C 유형 라우팅 정확도 ≥ 0.88 (PRD §05 domain)")
    void routingAccuracyMeetsThreshold() {
        UserAiContext ctx = LlmEvalSupport.chatContext();
        PromptLoader prompts = new PromptLoader();
        List<ExtractionStrategy> strategies =
                List.of(new KnowledgeExtraction(prompts), new TroubleshootingExtraction(prompts));
        TypeClassifier classifier = new TypeClassifier(prompts, strategies);

        List<Map<String, Object>> cases = EvalCases.load("eval/type-routing-c.json");
        List<String> misses = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            MemoryType expected = MemoryType.valueOf(EvalCases.str(c, "expectedType"));
            MemoryType actual = classifier.classify(EvalCases.str(c, "input"), ctx);
            if (expected != actual) {
                misses.add(EvalCases.str(c, "id") + ": 기대 " + expected + " · 실제 " + actual);
            }
        }

        LlmEvalSupport.assertAccuracy("C(유형 라우팅)", cases.size(), misses, THRESHOLD);
    }
}
