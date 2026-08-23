package com.recall.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.recall.common.prompt.PromptLoader;
import com.recall.common.type.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.ExtractionStrategy;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.knowledge.KnowledgeCard;
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
        public Class<? extends MemoryCard> cardType() {
            return KnowledgeCard.class;
        }

        @Override
        public MemoryCard extract(String maskedText, UserAiContext ctx) {
            calls++;
            return new KnowledgeCard("T" + calls, "", List.of(), List.of("f" + calls), "");
        }
    }

    private static LongContextExtractor extractor(CountingExtraction s2) {
        PromptLoader loader = mock(PromptLoader.class);
        when(loader.load(anyString())).thenReturn("merge prompt");
        return new LongContextExtractor(List.of(s2), new CardCodec(List.of(s2)), loader);
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

    /**
     * chat이 설정된(chatReady=true) ctx — {@code llm}만 바꿔 다양한 병합 결과를 시뮬레이션한다. embedding은 이 테스트들에서 쓰이지
     * 않는다.
     */
    private static UserAiContext ctx(LlmClient llm) {
        return new UserAiContext(1L, llm, mock(EmbeddingClient.class), true, true);
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

        // 이 유틸만은 필드 맵을 다룬다 — 스키마 무관 병합이라 유형별 필드 이름을 알지 않는 게 요점이다.
        // 병합 결과를 카드로 되돌리는 책임은 호출부(reduce)에 있고, 그 경로는 아래 Map-Reduce 테스트가 덮는다.
        Map<String, Object> merged = LongContextExtractor.mergeDeterministic(List.of(a, b));

        assertEquals("제목B", merged.get("title")); // 첫 값이 blank라 다음 non-blank
        assertEquals(List.of("f1", "f2", "f3"), merged.get("facts")); // union·중복 제거
    }

    @Test
    @DisplayName("mergeDeterministic 은 스키마를 전혀 모른다 — 모든 스칼라가 첫 non-blank(결말 판단은 유형이 병합 후에)")
    void schemaAgnosticFirstNonBlankForEveryScalar() {
        // 한때 유형이 지목한 키에 "마지막 non-blank"를 적용했는데(lastWinsFields), 조각 추출이 그 필드를
        // 항상 채우므로 사실상 "무조건 마지막 조각"이 됐고 결말을 말하지 않은 꼬리 조각의 기본값이
        // 앞의 판정을 덮었다. 이제 이 유틸은 위치 규칙 하나만 갖고, 결말 판단은 reconcileMerged 가 한다.
        Map<String, Object> early = new LinkedHashMap<>();
        early.put("status", "UNRESOLVED");
        early.put("title", "제목A");
        Map<String, Object> late = new LinkedHashMap<>();
        late.put("status", "RESOLVED");
        late.put("title", "제목B");

        Map<String, Object> merged = LongContextExtractor.mergeDeterministic(List.of(early, late));

        assertEquals("UNRESOLVED", merged.get("status"), "스칼라는 예외 없이 첫 non-blank");
        assertEquals("제목A", merged.get("title"));
    }

    // ── 라우팅 ──────────────────────────────────────────────

    @Test
    @DisplayName("짧은 원문은 단일 패스(S2 1회) — 기존 동작 그대로")
    void shortTextSinglePass() {
        CountingExtraction s2 = new CountingExtraction();
        KnowledgeCard result =
                (KnowledgeCard)
                        extractor(s2).extract(MemoryType.KNOWLEDGE, "짧은 원문", ctx(llm("무관", false)));
        assertEquals(1, s2.calls);
        assertEquals("T1", result.title());
    }

    @Test
    @DisplayName("긴 원문은 조각마다 S2(map) 후 병합 — LLM 미가용이면 결정론 병합으로 사실 union 보존")
    void longTextMapReduceWithDeterministicFallback() {
        CountingExtraction s2 = new CountingExtraction();
        String big = "x".repeat(30_000); // 30000자 → size 8000·overlap 500 → 4조각
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2).extract(MemoryType.KNOWLEDGE, big, ctx(llm("무관", false)));

        assertEquals(4, s2.calls, "조각 수만큼 map 추출");
        assertEquals(List.of("f1", "f2", "f3", "f4"), merged.facts(), "모든 조각의 사실 보존(union)");
        assertEquals("T1", merged.title());
    }

    @Test
    @DisplayName("긴 원문 + LLM 병합 가용 → 스칼라는 LLM, 리스트는 결정론 union")
    void longTextUsesLlmMerge() {
        // 리스트 병합 권한은 LLM 에게 없다: 합치기는 union 이라는 모호함 없는 연산이므로 결정론으로 둔다
        // (불변 원칙 4). LLM 이 낸 facts(m1·m2)는 버려지고 조각들의 union 이 들어간다.
        CountingExtraction s2 = new CountingExtraction();
        LlmClient merger = llm("{\"title\":\"병합됨\",\"facts\":[\"m1\",\"m2\"]}", true);
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2)
                                .extract(MemoryType.KNOWLEDGE, "x".repeat(30_000), ctx(merger));

        assertEquals("병합됨", merged.title(), "스칼라는 LLM 이 다듬은 값");
        assertEquals(List.of("f1", "f2", "f3", "f4"), merged.facts(), "리스트는 조각들의 union");
    }

    @Test
    @DisplayName("🟠 LLM 이 조각마다 한 건씩 골라 버려도(교차 유실) union 이 이긴다")
    void llmMergeCrossLossIsOverriddenByUnion() {
        // 개수 하한 검사만 있던 동안 통과했던 형태다: 조각이 f1·f2·f3·f4 인데 병합이 [f1,f3] 이면
        // 개수(2)가 가장 긴 단일 조각(1)보다 크므로 하한을 만족하면서 f2·f4 를 잃는다. 개수를 union
        // 수로 올려도 정상적인 dedup(겹침 청킹이 만든 표현만 다른 중복)을 유실로 오판하고, 소속으로
        // 증명하려 해도 LLM 이 문장을 다시 쓰면 동일성 비교가 성립하지 않는다 — 그래서 검사 대신
        // 리스트 소유권을 결정론 쪽으로 옮겼다. 이제 이 응답은 <b>검사에 걸리는 게 아니라 무시된다</b>.
        CountingExtraction s2 = new CountingExtraction();
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2)
                                .extract(
                                        MemoryType.KNOWLEDGE,
                                        "x".repeat(30_000),
                                        ctx(
                                                llm(
                                                        "{\"title\":\"병합됨\",\"facts\":[\"f1\",\"f3\"]}",
                                                        true)));

        assertEquals(List.of("f1", "f2", "f3", "f4"), merged.facts(), "버려진 f2·f4 가 union 으로 살아난다");
    }

    @Test
    @DisplayName("LLM 병합 응답이 JSON 이어도 카드 모양이 아니면(키 집합 불일치) 결정론 병합으로 격하")
    void llmMergeWrongShapeFallsBack() {
        // 문법만 맞는 응답(카드를 한 겹 감싼 형태)은 파싱을 통과해 그대로 structured 가 됐다 —
        // 이 경로는 유형 스키마를 거치지 않으므로 title·facts 가 사라진 카드가 하류로 새어 나갔다.
        CountingExtraction s2 = new CountingExtraction();
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2)
                                .extract(
                                        MemoryType.KNOWLEDGE,
                                        "x".repeat(30_000),
                                        ctx(
                                                llm(
                                                        "{\"merged\":{\"title\":\"병합됨\",\"facts\":[\"m1\"]}}",
                                                        true)));

        assertEquals(4, merged.facts().size(), "결정론 병합으로 격하돼 사실이 보존된다");
        assertEquals("T1", merged.title());
    }

    @Test
    @DisplayName("🟠 LLM 병합이 리스트를 통째로 떨어뜨려도 union 이 복구한다 — 사실 유실 금지")
    void llmMergeDroppingListIsRecovered() {
        // 모양은 맞지만(title 공유) 부분 카드가 채웠던 facts 가 빠진 응답. 전에는 모양 검사만 해서
        // 이 응답이 통과했고, 코덱이 빠진 리스트를 빈 리스트로 정규화해 4개 조각의 사실이 전부 사라졌다.
        //
        // 지금은 리스트를 결정론 union 이 소유하므로 <b>격하 없이 복구된다</b> — 응답에 키가 없어도
        // 채워 넣는다. 카드 전체를 버리지 않으니 LLM 이 다듬은 스칼라(title)는 그대로 쓴다:
        // 사실은 union 이 지키고 요약 품질은 LLM 이 올린다.
        CountingExtraction s2 = new CountingExtraction();
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2)
                                .extract(
                                        MemoryType.KNOWLEDGE,
                                        "x".repeat(30_000),
                                        ctx(llm("{\"title\":\"병합됨\"}", true)));

        assertEquals(List.of("f1", "f2", "f3", "f4"), merged.facts(), "union 으로 복구돼 사실이 보존된다");
        assertEquals("병합됨", merged.title(), "리스트만 되돌리고 카드는 버리지 않는다");
    }

    @Test
    @DisplayName("부분 카드가 비워 둔 필드는 병합 결과에서도 비어 있어도 통과한다(유실이 아니다)")
    void llmMergeMayLeaveAlreadyEmptyFieldsEmpty() {
        // 내용 검사는 "부분 카드가 채웠던" 필드만 본다 — CountingExtraction 은 summary·document 를
        // 비워 두므로, 병합 응답이 그 둘을 안 채워도 거절하지 않는다. 그렇지 않으면 정상 병합이
        // 전부 격하돼 LLM 병합이 사실상 죽는다.
        CountingExtraction s2 = new CountingExtraction();
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2)
                                .extract(
                                        MemoryType.KNOWLEDGE,
                                        "x".repeat(30_000),
                                        ctx(
                                                llm(
                                                        "{\"title\":\"병합됨\",\"facts\":[\"m1\",\"m2\"]}",
                                                        true)));

        assertEquals("병합됨", merged.title(), "LLM 병합 결과를 그대로 쓴다");
        assertEquals(List.of("f1", "f2", "f3", "f4"), merged.facts(), "리스트는 union");
    }

    @Test
    @DisplayName("🟠 LLM 이 리스트를 껍데기로 줄여도([\"\"]) 그 응답을 쓰지 않는다")
    void llmMergeShrinkingListIsIgnored() {
        // "비어있지 않다"만 보면 조각마다 1건씩 있던 facts 가 빈 문자열 1건으로 줄어도 통과했고,
        // 코덱이 그것을 정규화해 시도·사실 이력이 실질적으로 사라졌다(PRD 🟠 중대 실패).
        // 개수 검사로 막던 것을 이제는 소유권으로 막는다 — 이 응답의 facts 는 읽히지도 않는다.
        CountingExtraction s2 = new CountingExtraction();
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2)
                                .extract(
                                        MemoryType.KNOWLEDGE,
                                        "x".repeat(30_000),
                                        ctx(llm("{\"title\":\"병합됨\",\"facts\":[\"\"]}", true)));

        assertEquals(List.of("f1", "f2", "f3", "f4"), merged.facts(), "union 이 들어가 항목이 보존된다");
    }

    @Test
    @DisplayName("LLM 병합 응답에 JSON 없으면 결정론 병합으로 격하")
    void llmMergeGarbageFallsBack() {
        CountingExtraction s2 = new CountingExtraction();
        KnowledgeCard merged =
                (KnowledgeCard)
                        extractor(s2)
                                .extract(
                                        MemoryType.KNOWLEDGE,
                                        "x".repeat(30_000),
                                        ctx(llm("병합 못 하겠어요", true)));
        assertEquals(4, merged.facts().size()); // 결정론 union
    }
}
