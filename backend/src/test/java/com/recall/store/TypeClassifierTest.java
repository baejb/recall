package com.recall.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.ExtractionStrategy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장 경로 유형 라우팅(S2 앞단) — 확률적(LLM) 단계지만 <b>지원 유형 한정·격하·미호출 조건</b>은 결정론이라 단위테스트로 고정한다.
 *
 * <p>등록된 추출 전략이 곧 지원 유형이다(자가 등록) — 새 유형 전략이 추가되면 분류가 자동으로 켜지고, 유형이 하나뿐이면 LLM을 부르지 않는다.
 */
class TypeClassifierTest {

    private static final TypeClassifier CLASSIFIER_FOR_BOTH =
            classifierFor(MemoryType.KNOWLEDGE, MemoryType.TROUBLESHOOTING);

    private static TypeClassifier classifierFor(MemoryType... types) {
        List<ExtractionStrategy> strategies =
                Arrays.stream(types).map(TypeClassifierTest::extractionFor).toList();
        return new TypeClassifier(new PromptLoader(), strategies);
    }

    private static ExtractionStrategy extractionFor(MemoryType type) {
        return new ExtractionStrategy() {
            @Override
            public MemoryType supports() {
                return type;
            }

            @Override
            public Map<String, Object> extract(String maskedText, UserAiContext ctx) {
                return Map.of();
            }
        };
    }

    private static UserAiContext ctxWithResponse(String response) {
        return new UserAiContext(
                1L, (system, user) -> response, mock(EmbeddingClient.class), true, true);
    }

    @Test
    @DisplayName("LLM이 지목한 유형으로 라우팅한다")
    void routesToTypeNamedByLlm() {
        assertEquals(
                MemoryType.TROUBLESHOOTING,
                CLASSIFIER_FOR_BOTH.classify("exit 137로 죽어요", ctxWithResponse("TROUBLESHOOTING")));
        assertEquals(
                MemoryType.KNOWLEDGE,
                CLASSIFIER_FOR_BOTH.classify("RRF 정리", ctxWithResponse("KNOWLEDGE")));
    }

    @Test
    @DisplayName("이름 앞뒤에 잡소리가 섞여도 유형 이름으로 매칭한다")
    void matchesTypeNameInNoisyOutput() {
        assertEquals(
                MemoryType.TROUBLESHOOTING,
                CLASSIFIER_FOR_BOTH.classify("원문", ctxWithResponse("이 메모는 troubleshooting 입니다.")));
    }

    @Test
    @DisplayName("지원 유형이 1개면 LLM을 부르지 않고 그 유형(비용·지연 절약)")
    void skipsLlmWhenSingleType() {
        LlmClient never = mock(LlmClient.class);
        UserAiContext ctx = new UserAiContext(1L, never, mock(EmbeddingClient.class), true, true);

        assertEquals(
                MemoryType.KNOWLEDGE,
                classifierFor(MemoryType.KNOWLEDGE).classify("exit 137로 죽어요", ctx));
        verify(never, never()).complete(any(), any());
    }

    @Test
    @DisplayName("모르는/미지원 유형을 내면 기본 유형으로 격하한다(조용한 실패 금지 — 로그로 드러냄)")
    void degradesOnUnsupportedOutput() {
        assertEquals(
                MemoryType.KNOWLEDGE,
                CLASSIFIER_FOR_BOTH.classify("원문", ctxWithResponse("COMMAND_CODE")));
        assertEquals(
                MemoryType.KNOWLEDGE, CLASSIFIER_FOR_BOTH.classify("원문", ctxWithResponse("글쎄요")));
        assertEquals(
                MemoryType.KNOWLEDGE, CLASSIFIER_FOR_BOTH.classify("원문", ctxWithResponse(null)));
    }

    @Test
    @DisplayName("설정 완료 후 외부 LLM 호출 실패는 기본 유형으로 격하한다(요청을 죽이지 않음)")
    void degradesOnLlmFailure() {
        UserAiContext boom =
                new UserAiContext(
                        1L,
                        (system, user) -> {
                            throw new RuntimeException("external boom");
                        },
                        mock(EmbeddingClient.class),
                        true,
                        true);

        assertEquals(MemoryType.KNOWLEDGE, CLASSIFIER_FOR_BOTH.classify("원문", boom));
    }

    @Test
    @DisplayName("마스킹된 원문과 후보 유형 목록이 프롬프트로 전달된다")
    void promptCarriesMaskedTextAndCandidates() {
        AtomicReference<String> userPrompt = new AtomicReference<>();
        UserAiContext ctx =
                new UserAiContext(
                        1L,
                        (system, user) -> {
                            userPrompt.set(user);
                            return "KNOWLEDGE";
                        },
                        mock(EmbeddingClient.class),
                        true,
                        true);

        CLASSIFIER_FOR_BOTH.classify("API_KEY=[MASKED] 관련 메모", ctx);

        String prompt = userPrompt.get();
        assertTrue(prompt.contains("API_KEY=[MASKED] 관련 메모"), "마스킹된 원문이 프롬프트에 실린다");
        assertTrue(prompt.contains("KNOWLEDGE"), "후보 유형 목록");
        assertTrue(prompt.contains("TROUBLESHOOTING"), "후보 유형 목록");
    }

    @Test
    @DisplayName("긴 원문은 라우팅 입력만 앞부분으로 자른다(추출은 전문을 처리하므로 내용 유실이 아니다)")
    void trimsRoutingInputOnly() {
        AtomicReference<String> userPrompt = new AtomicReference<>();
        UserAiContext ctx =
                new UserAiContext(
                        1L,
                        (system, user) -> {
                            userPrompt.set(user);
                            return "KNOWLEDGE";
                        },
                        mock(EmbeddingClient.class),
                        true,
                        true);
        String head = "도입부 증상 서술";
        String tail = "여기가꼬리표시";
        String longText = head + "가".repeat(TypeClassifier.ROUTING_MAX_CHARS) + tail;

        CLASSIFIER_FOR_BOTH.classify(longText, ctx);

        assertTrue(userPrompt.get().contains(head), "앞부분은 라우팅 판단에 쓰인다");
        assertFalse(userPrompt.get().contains(tail), "상한을 넘는 뒷부분은 라우팅 프롬프트에 넣지 않는다");
    }
}
