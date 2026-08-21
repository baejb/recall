package com.recall.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.recall.common.AiNotConfiguredException;
import com.recall.common.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.Memory;
import com.recall.memory.type.AnswerContribution;
import com.recall.memory.type.knowledge.KnowledgeAnswer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A 그라운딩 프롬프트 + RR(리랭크)/C(분류) 파싱·재정렬·격하의 결정론 검증(순수 로직 — DB·실LLM 불필요). */
class QueryPipelineTest {

    /** rerank/classify는 searchService를 쓰지 않으므로 null로 둔다(생성만; 호출 안 함). */
    private static QueryPipeline pipelineWith(List<AnswerContribution> answers) {
        return new QueryPipeline(null, answers);
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
            public String render(Map<String, Object> memory) {
                return "";
            }
        };
    }

    private static Memory mem(int i) {
        return Memory.transientCard(MemoryType.KNOWLEDGE, "t" + i, "{\"title\":\"t" + i + "\"}");
    }

    private static List<String> titles(List<Memory> memories) {
        return memories.stream().map(Memory::getTitle).toList();
    }

    // ── A 그라운딩 프롬프트 ──────────────────────────────────

    @Test
    @DisplayName("A 프롬프트에 질문 + 번호 매긴 근거(유형 전략이 렌더한 제목·요약·사실)가 담긴다")
    void evidencePromptCarriesQuestionAndEvidence() {
        Memory m =
                Memory.transientCard(
                        MemoryType.KNOWLEDGE,
                        "게이트웨이 분리",
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
        List<Memory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)), ctx);
        assertEquals(List.of("t2", "t1", "t3"), titles(out)); // 2 먼저, 나머지 원순서 보존
    }

    @Test
    @DisplayName("rerank: 파싱 실패는 W 순서 유지(격하)")
    void rerankDegradesOnGarbage() {
        QueryPipeline p = pipelineWith(List.of());
        UserAiContext ctx = ctxWithLlm((s, u) -> "관련도를 못 정하겠어요");
        List<Memory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)), ctx);
        assertEquals(List.of("t1", "t2", "t3"), titles(out));
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
        List<Memory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)), ctx);
        assertEquals(List.of("t1", "t2", "t3"), titles(out));
    }

    @Test
    @DisplayName("rerank: 상위 RR_OUTPUT_MAX(6)개로 자른다")
    void rerankCapsOutput() {
        QueryPipeline p = pipelineWith(List.of());
        UserAiContext ctx = ctxWithLlm((s, u) -> "[8,7,6,5,4,3,2,1]");
        List<Memory> in = List.of(mem(1), mem(2), mem(3), mem(4), mem(5), mem(6), mem(7), mem(8));
        List<Memory> out = p.rerank("q", in, ctx);
        assertEquals(6, out.size());
        assertEquals("t8", out.get(0).getTitle());
    }

    @Test
    @DisplayName("rerank: 후보 1개면 chat 호출 없이 그대로(미설정 ctx라도 통과)")
    void rerankSkipsForSingle() {
        QueryPipeline p = pipelineWith(List.of());
        List<Memory> in = List.of(mem(1));
        assertEquals(in, p.rerank("q", in, ctxChatNotConfigured()));
    }

    // ── C 분류 ──────────────────────────────────────────────

    @Test
    @DisplayName("parseType: 트러블슈팅 신호가 있으면 TS, 없으면 기본 KNOWLEDGE")
    void parsesType() {
        assertEquals(MemoryType.TROUBLESHOOTING, QueryPipeline.parseType("TROUBLESHOOTING"));
        assertEquals(MemoryType.TROUBLESHOOTING, QueryPipeline.parseType("트러블슈팅 같아요"));
        assertEquals(MemoryType.KNOWLEDGE, QueryPipeline.parseType("KNOWLEDGE"));
        assertEquals(MemoryType.KNOWLEDGE, QueryPipeline.parseType("아무말"));
        assertEquals(MemoryType.KNOWLEDGE, QueryPipeline.parseType(null));
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
