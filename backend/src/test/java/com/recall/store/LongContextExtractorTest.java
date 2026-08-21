package com.recall.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.llm.LlmClient;
import com.recall.memory.type.ExtractionStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S3 긴맥락 Map-Reduce — 청킹(전체 커버·결정론), 결정론 병합(union), 라우팅(짧음→단일패스 / 김→map-reduce), Reduce 격하. */
class LongContextExtractorTest {

    /** 호출을 세고 조각마다 구분되는 카드를 내는 가짜 S2 추출. */
    private static final class CountingExtraction implements ExtractionStrategy {
        int calls = 0;

        @Override
        public MemoryType supports() {
            return MemoryType.KNOWLEDGE;
        }

        @Override
        public Map<String, Object> extract(String maskedText) {
            calls++;
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("title", "T" + calls);
            card.put("facts", new ArrayList<>(List.of("f" + calls)));
            return card;
        }
    }

    private static LongContextExtractor extractor(CountingExtraction s2, LlmClient llm) {
        PromptLoader loader = mock(PromptLoader.class);
        when(loader.load(anyString())).thenReturn("merge prompt");
        return new LongContextExtractor(List.of(s2), llm, loader);
    }

    private static LlmClient llm(String response, boolean available) {
        return new LlmClient() {
            @Override
            public String complete(String system, String user) {
                return response;
            }

            @Override
            public boolean available() {
                return available;
            }
        };
    }

    // ── 청킹(🟢 결정론, truncation 없음) ─────────────────────

    @Test
    @DisplayName("chunk: 짧으면 1조각, 겹침 윈도우로 전체를 커버(마지막 조각이 끝까지)")
    void chunksCoverWholeText() {
        assertEquals(List.of("abc"), LongContextExtractor.chunk("abc", 10, 2));

        String text = "0123456789ABCDEFGHIJ"; // 20자
        List<String> chunks = LongContextExtractor.chunk(text, 8, 2); // step=6
        // i=0[0,8), i=6[6,14), i=12[12,20) → 3조각, 전체 커버
        assertEquals(3, chunks.size());
        assertTrue(text.startsWith(chunks.get(0)), "첫 조각이 원문 시작");
        assertTrue(text.endsWith(chunks.get(chunks.size() - 1)), "마지막 조각이 원문 끝까지(truncation 없음)");
        assertEquals("01234567", chunks.get(0));
        assertEquals("CDEFGHIJ", chunks.get(2));
    }

    @Test
    @DisplayName("chunk: overlap≥size 여도 무한 루프 없이 진행")
    void chunkGuardsInfiniteLoop() {
        List<String> chunks = LongContextExtractor.chunk("0123456789", 4, 10); // step=max(1,-6)=1
        assertFalse(chunks.isEmpty());
        assertTrue("0123456789".endsWith(chunks.get(chunks.size() - 1)));
    }

    // ── 결정론 병합(격하) ────────────────────────────────────

    @Test
    @DisplayName("mergeDeterministic: 리스트는 중복 제거 union, 스칼라는 첫 non-blank")
    void mergesDeterministically() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", "");
        a.put("facts", List.of("f1", "f2"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", "제목B");
        b.put("facts", List.of("f2", "f3"));

        Map<String, Object> merged = LongContextExtractor.mergeDeterministic(List.of(a, b));

        assertEquals("제목B", merged.get("title")); // 첫 값이 blank라 다음 non-blank
        assertEquals(List.of("f1", "f2", "f3"), merged.get("facts")); // union·중복 제거
    }

    // ── 라우팅 ──────────────────────────────────────────────

    @Test
    @DisplayName("짧은 원문은 단일 패스(S2 1회) — 기존 동작 그대로")
    void shortTextSinglePass() {
        CountingExtraction s2 = new CountingExtraction();
        Map<String, Object> result =
                extractor(s2, llm("무관", false)).extract(MemoryType.KNOWLEDGE, "짧은 원문");
        assertEquals(1, s2.calls);
        assertEquals("T1", result.get("title"));
    }

    @Test
    @DisplayName("긴 원문은 조각마다 S2(map) 후 병합 — LLM 미가용이면 결정론 병합으로 사실 union 보존")
    void longTextMapReduceWithDeterministicFallback() {
        CountingExtraction s2 = new CountingExtraction();
        String big = "x".repeat(30_000); // 30000자 → size 8000·overlap 500 → 4조각
        Map<String, Object> merged =
                extractor(s2, llm("무관", false)).extract(MemoryType.KNOWLEDGE, big);

        assertEquals(4, s2.calls, "조각 수만큼 map 추출");
        assertEquals(List.of("f1", "f2", "f3", "f4"), merged.get("facts"), "모든 조각의 사실 보존(union)");
        assertEquals("T1", merged.get("title"));
    }

    @Test
    @DisplayName("긴 원문 + LLM 병합 가용 → LLM이 병합한 카드 사용")
    void longTextUsesLlmMerge() {
        CountingExtraction s2 = new CountingExtraction();
        LlmClient merger = llm("{\"title\":\"병합됨\",\"facts\":[\"m1\",\"m2\"]}", true);
        Map<String, Object> merged =
                extractor(s2, merger).extract(MemoryType.KNOWLEDGE, "x".repeat(30_000));

        assertEquals("병합됨", merged.get("title"));
        assertEquals(List.of("m1", "m2"), merged.get("facts"));
    }

    @Test
    @DisplayName("LLM 병합 응답에 JSON 없으면 결정론 병합으로 격하")
    void llmMergeGarbageFallsBack() {
        CountingExtraction s2 = new CountingExtraction();
        Map<String, Object> merged =
                extractor(s2, llm("병합 못 하겠어요", true))
                        .extract(MemoryType.KNOWLEDGE, "x".repeat(30_000));
        assertEquals(4, ((List<?>) merged.get("facts")).size()); // 결정론 union
    }
}
