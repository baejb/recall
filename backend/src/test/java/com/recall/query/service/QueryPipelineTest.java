package com.recall.query.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.exception.AiNotConfiguredException;
import com.recall.common.prompt.PromptLoader;
import com.recall.common.type.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.StoredMemory;
import com.recall.memory.type.AnswerContribution;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.knowledge.KnowledgeAnswer;
import com.recall.memory.type.knowledge.KnowledgeExtraction;
import com.recall.search.HybridSearchService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A 그라운딩 프롬프트 + RR(리랭크)/C(분류) 파싱·재정렬·격하의 결정론 검증(순수 로직 — DB·실LLM 불필요). */
class QueryPipelineTest {

    /** rerank/classify는 searchService를 쓰지 않으므로 null로 둔다(생성만; 호출 안 함). */
    private static QueryPipeline pipelineWith(List<AnswerContribution> answers) {
        return new QueryPipeline(null, cardCodec(), answers);
    }

    /**
     * 저장된 structured JSON을 카드로 되읽는 코덱. 이 테스트의 근거는 모두 knowledge 카드라 knowledge 추출 전략만 등록한다 — 코덱은
     * {@code ExtractionStrategy.cardType()}으로 유형→카드 클래스를 찾는다.
     */
    private static CardCodec cardCodec() {
        return new CardCodec(List.of(new KnowledgeExtraction(new PromptLoader())));
    }

    /**
     * chat이 설정된(chatReady=true) ctx — {@code llm}만 바꿔 다양한 LLM 응답/실패를 시뮬레이션한다. embedding은 이 테스트들에서
     * 쓰이지 않는다.
     */
    private static UserAiContext ctxWithLlm(LlmClient llm) {
        return new UserAiContext(1L, llm, mock(EmbeddingClient.class), true, false);
    }

    /** chat 미설정(chatReady=false) ctx — requireChat()이 던지는 방어적 가드를 검증할 때 쓴다. */
    private static UserAiContext ctxChatNotConfigured() {
        return new UserAiContext(
                1L, mock(LlmClient.class), mock(EmbeddingClient.class), false, false);
    }

    private static AnswerContribution answerFor(MemoryType t) {
        return new AnswerContribution() {
            @Override
            public MemoryType supports() {
                return t;
            }

            @Override
            public String render(MemoryCard card) {
                return "";
            }
        };
    }

    private static StoredMemory mem(int i) {
        return new StoredMemory(i, MemoryType.KNOWLEDGE, "{\"title\":\"t" + i + "\"}");
    }

    /**
     * 재정렬 결과는 <b>id 순서</b>로 확인한다 — 모듈 계약({@code StoredMemory})은 제목을 나르지 않는다(제목은 카드 안에 있고, 렌더는 유형
     * 전략이 한다). rerank 가 하는 일은 후보의 순서를 바꾸는 것이므로 id 열이 곧 검증 대상이다.
     */
    private static List<Long> ids(List<StoredMemory> memories) {
        return memories.stream().map(StoredMemory::id).toList();
    }

    // ── A 그라운딩 프롬프트 ──────────────────────────────────

    @Test
    @DisplayName("A 프롬프트에 질문 + 번호 매긴 근거(유형 전략이 렌더한 제목·요약·사실)가 담긴다")
    void evidencePromptCarriesQuestionAndEvidence() {
        StoredMemory m =
                new StoredMemory(
                        1,
                        MemoryType.KNOWLEDGE,
                        "{\"title\":\"게이트웨이 분리\",\"summary\":\"토폴로지 분리는 끝났다\","
                                + "\"facts\":[\"별도 배포 단위\",\"REST·Kafka로만 연결\"]}");

        String prompt =
                pipelineWith(List.of(new KnowledgeAnswer()))
                        .buildEvidencePrompt("남은 과제가 뭐였지?", List.of(m));

        assertTrue(prompt.contains("남은 과제가 뭐였지?"), "질문 포함");
        assertTrue(prompt.contains("[1]"), "근거 번호");
        assertTrue(prompt.contains("게이트웨이 분리"), "제목");
        assertTrue(prompt.contains("토폴로지 분리는 끝났다"), "요약");
        assertTrue(prompt.contains("별도 배포 단위"), "사실1");
        assertTrue(prompt.contains("REST·Kafka로만 연결"), "사실2");
    }

    // ── R retrieve (유형 배타필터 false-negative 보정) ──────────

    @Test
    @DisplayName("retrieve: 분류 유형에 결과 없으면 나머지 등록 유형을 재검색해 근거를 찾는다(기록 없음 오판 방지)")
    void retrieveFallsBackToOtherTypesWhenPrimaryEmpty() {
        HybridSearchService search = mock(HybridSearchService.class);
        UserAiContext ctx = ctxWithLlm((s, u) -> "");
        when(search.search("q", MemoryType.KNOWLEDGE, ctx)).thenReturn(List.of());
        when(search.search("q", MemoryType.TROUBLESHOOTING, ctx)).thenReturn(List.of(mem(2)));

        QueryPipeline p = new QueryPipeline(search, cardCodec(), BOTH_TYPES);
        List<StoredMemory> out = p.retrieve("q", MemoryType.KNOWLEDGE, ctx);

        assertEquals(List.of(2L), ids(out), "분류가 KNOWLEDGE 였어도 TROUBLESHOOTING 근거를 찾아야 한다");
    }

    @Test
    @DisplayName("retrieve: 분류 유형에 결과가 있으면 다른 유형을 재검색하지 않는다(빠른 경로 유지)")
    void retrieveDoesNotFallBackWhenPrimaryHasResults() {
        HybridSearchService search = mock(HybridSearchService.class);
        UserAiContext ctx = ctxWithLlm((s, u) -> "");
        when(search.search("q", MemoryType.KNOWLEDGE, ctx)).thenReturn(List.of(mem(1)));

        QueryPipeline p = new QueryPipeline(search, cardCodec(), BOTH_TYPES);
        List<StoredMemory> out = p.retrieve("q", MemoryType.KNOWLEDGE, ctx);

        assertEquals(List.of(1L), ids(out));
        verify(search, org.mockito.Mockito.never()).search("q", MemoryType.TROUBLESHOOTING, ctx);
    }

    @Test
    @DisplayName("retrieve: 전 유형에서 결과 없으면 빈 결과(진짜 기록 없음)")
    void retrieveEmptyWhenNoTypeHasResults() {
        HybridSearchService search = mock(HybridSearchService.class);
        UserAiContext ctx = ctxWithLlm((s, u) -> "");
        when(search.search(anyString(), any(), any())).thenReturn(List.of());

        QueryPipeline p = new QueryPipeline(search, cardCodec(), BOTH_TYPES);
        assertTrue(p.retrieve("q", MemoryType.KNOWLEDGE, ctx).isEmpty());
    }

    // ── RR parseOrder ───────────────────────────────────────

    @Test
    @DisplayName("parseOrder: 범위 밖·중복·주변 텍스트를 걸러 유효 순서만")
    void parsesOrder() {
        assertEquals(List.of(3, 1, 2), QueryPipeline.parseOrder("[3,1,2]", 3));
        assertEquals(List.of(3, 1), QueryPipeline.parseOrder("[3,1,99]", 5)); // 99 범위 밖
        assertEquals(List.of(2, 1), QueryPipeline.parseOrder("순서: [2, 1] 입니다", 3));
        assertEquals(List.of(1, 2), QueryPipeline.parseOrder("[1,1,2]", 3)); // 중복 제거
        assertTrue(QueryPipeline.parseOrder("배열 없음", 3).isEmpty());
        assertTrue(QueryPipeline.parseOrder(null, 3).isEmpty());
    }

    // ── RR rerank ───────────────────────────────────────────

    @Test
    @DisplayName("rerank: LLM 관련도 순으로 재정렬하고, 누락 후보는 뒤에 보존")
    void reranksByLlmOrderAndKeepsMissing() {
        QueryPipeline p = pipelineWith(List.of());
        UserAiContext ctx = ctxWithLlm((s, u) -> "[2]"); // 2번만 지목
        List<StoredMemory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)), ctx);
        assertEquals(List.of(2L, 1L, 3L), ids(out)); // 2 먼저, 나머지 원순서 보존
    }

    @Test
    @DisplayName("rerank: 파싱 실패는 W 순서 유지(격하)")
    void rerankDegradesOnGarbage() {
        QueryPipeline p = pipelineWith(List.of());
        UserAiContext ctx = ctxWithLlm((s, u) -> "관련도를 못 정하겠어요");
        List<StoredMemory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)), ctx);
        assertEquals(List.of(1L, 2L, 3L), ids(out));
    }

    @Test
    @DisplayName("rerank: 설정 완료 후 외부 LLM 호출 실패는 W 순서 유지(격하, 미설정 차단과 다름)")
    void rerankDegradesOnException() {
        QueryPipeline p = pipelineWith(List.of());
        UserAiContext ctx =
                ctxWithLlm(
                        (s, u) -> {
                            throw new RuntimeException("boom");
                        });
        List<StoredMemory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)), ctx);
        assertEquals(List.of(1L, 2L, 3L), ids(out));
    }

    @Test
    @DisplayName("rerank: 상위 RR_OUTPUT_MAX(6)개로 자른다")
    void rerankCapsOutput() {
        QueryPipeline p = pipelineWith(List.of());
        UserAiContext ctx = ctxWithLlm((s, u) -> "[8,7,6,5,4,3,2,1]");
        List<StoredMemory> in =
                List.of(mem(1), mem(2), mem(3), mem(4), mem(5), mem(6), mem(7), mem(8));
        List<StoredMemory> out = p.rerank("q", in, ctx);
        assertEquals(6, out.size());
        assertEquals(8L, out.get(0).id());
    }

    @Test
    @DisplayName("rerank: 후보 1개면 chat 호출 없이 그대로(미설정 ctx라도 통과)")
    void rerankSkipsForSingle() {
        QueryPipeline p = pipelineWith(List.of());
        List<StoredMemory> in = List.of(mem(1));
        assertEquals(in, p.rerank("q", in, ctxChatNotConfigured()));
    }

    // ── C 분류 ──────────────────────────────────────────────

    /** 지원 유형 = 등록된 답변 전략의 유형(현재 둘). parseType 은 이 집합 안에서만 고른다. */
    private static final Set<MemoryType> SUPPORTED =
            Set.of(MemoryType.KNOWLEDGE, MemoryType.TROUBLESHOOTING);

    @Test
    @DisplayName("parseType: 지원 유형 이름이 정확히 하나 등장하면 그 유형, 아니면 null(격하는 호출부가)")
    void parsesType() {
        assertEquals(
                MemoryType.TROUBLESHOOTING, QueryPipeline.parseType("TROUBLESHOOTING", SUPPORTED));
        assertEquals(MemoryType.KNOWLEDGE, QueryPipeline.parseType("KNOWLEDGE", SUPPORTED));
        assertEquals(
                MemoryType.TROUBLESHOOTING,
                QueryPipeline.parseType("troubleshooting 입니다", SUPPORTED),
                "대소문자·주변 산문은 허용");
        // 유형 이름 없음/모름 → null. 예전엔 하드코딩된 한국어 키워드 표로 "트러블"을 잡았지만, 그 표는 유형이
        // 늘어도 확장되지 않아 새 유형을 조용히 KNOWLEDGE 로 떨어뜨렸다(그래서 규칙을 이름 매칭으로 통일).
        assertNull(QueryPipeline.parseType("아무말", SUPPORTED));
        assertNull(QueryPipeline.parseType("트러블슈팅 같아요", SUPPORTED));
        assertNull(QueryPipeline.parseType(null, SUPPORTED));
    }

    @Test
    @DisplayName("parseType: 두 유형 이름이 함께 등장하면 모호로 보고 null — 부정문에 걸려 오분류되지 않게")
    void parseTypeRejectsAmbiguousOutput() {
        assertNull(QueryPipeline.parseType("KNOWLEDGE 가 아니라 TROUBLESHOOTING 입니다", SUPPORTED));
    }

    private static final List<AnswerContribution> BOTH_TYPES =
            List.of(answerFor(MemoryType.KNOWLEDGE), answerFor(MemoryType.TROUBLESHOOTING));

    @Test
    @DisplayName("classify: 지원 유형이 2개면 LLM 출력대로 판정")
    void classifyUsesLlmWhenMultipleTypes() {
        QueryPipeline p = pipelineWith(BOTH_TYPES);
        assertEquals(
                MemoryType.TROUBLESHOOTING,
                p.classify("그 403 에러 어떻게 풀었지?", ctxWithLlm((s, u) -> "TROUBLESHOOTING")));
        assertEquals(
                MemoryType.KNOWLEDGE, p.classify("RRF가 뭐야?", ctxWithLlm((s, u) -> "KNOWLEDGE")));
    }

    @Test
    @DisplayName("classify: 지원 유형이 1개면 chat 호출 없이 그 유형(미설정 ctx라도 통과 — 안전 가드)")
    void classifySkipsLlmWhenSingleType() {
        QueryPipeline p = pipelineWith(List.of(answerFor(MemoryType.KNOWLEDGE)));
        assertEquals(MemoryType.KNOWLEDGE, p.classify("그 403 에러 어떻게 풀었지?", ctxChatNotConfigured()));
    }

    @Test
    @DisplayName(
            "classify: chat 미설정 ctx + 유형 2개 이상 → AiNotConfiguredException(방어적 가드 — 정상 흐름은 조회 입구가 선차단)")
    void classifyThrowsWhenChatNotReady() {
        QueryPipeline p = pipelineWith(BOTH_TYPES);
        assertThrows(AiNotConfiguredException.class, () -> p.classify("q", ctxChatNotConfigured()));
    }

    @Test
    @DisplayName("classify: 설정 완료 후 외부 LLM 호출 실패 → 기본 KNOWLEDGE(격하, 미설정 차단과 다름)")
    void classifyDegradesOnException() {
        QueryPipeline p = pipelineWith(BOTH_TYPES);
        UserAiContext ctx =
                ctxWithLlm(
                        (s, u) -> {
                            throw new RuntimeException("boom");
                        });
        assertEquals(MemoryType.KNOWLEDGE, p.classify("q", ctx));
    }
}
