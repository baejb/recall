package com.recall.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.llm.LlmClient;
import com.recall.memory.Memory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A 그라운딩 프롬프트 + RR(리랭크) 파싱/재정렬/격하의 결정론 검증(순수 로직 — DB·실LLM 불필요). */
class QueryPipelineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** rerank/compose는 searchService를 쓰지 않으므로 null로 둔다(생성만; 호출 안 함). */
    private QueryPipeline pipelineWithLlm(LlmClient llm) {
        return new QueryPipeline(null, List.of(), llm);
    }

    private static Memory mem(int i) {
        return new Memory(null, MemoryType.KNOWLEDGE, "t" + i, "{\"title\":\"t" + i + "\"}");
    }

    private static List<String> titles(List<Memory> memories) {
        return memories.stream().map(Memory::getTitle).toList();
    }

    // ── A 그라운딩 프롬프트 ──────────────────────────────────

    @Test
    @DisplayName("A 프롬프트에 질문 + 번호 매긴 근거의 제목·요약·사실이 담긴다")
    void evidencePromptCarriesQuestionAndEvidence() {
        Memory m =
                new Memory(
                        null,
                        MemoryType.KNOWLEDGE,
                        "게이트웨이 분리",
                        "{\"title\":\"게이트웨이 분리\",\"summary\":\"토폴로지 분리는 끝났다\","
                                + "\"facts\":[\"별도 배포 단위\",\"REST·Kafka로만 연결\"]}");

        String prompt = QueryPipeline.buildEvidencePrompt("남은 과제가 뭐였지?", List.of(m), mapper);

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
        QueryPipeline p = pipelineWithLlm((s, u) -> "[2]"); // 2번만 지목
        List<Memory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)));
        assertEquals(List.of("t2", "t1", "t3"), titles(out)); // 2 먼저, 나머지 원순서 보존
    }

    @Test
    @DisplayName("rerank: 파싱 실패는 W 순서 유지(격하)")
    void rerankDegradesOnGarbage() {
        QueryPipeline p = pipelineWithLlm((s, u) -> "관련도를 못 정하겠어요");
        List<Memory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)));
        assertEquals(List.of("t1", "t2", "t3"), titles(out));
    }

    @Test
    @DisplayName("rerank: LLM 예외는 W 순서 유지(격하)")
    void rerankDegradesOnException() {
        QueryPipeline p =
                pipelineWithLlm(
                        (s, u) -> {
                            throw new RuntimeException("boom");
                        });
        List<Memory> out = p.rerank("q", List.of(mem(1), mem(2), mem(3)));
        assertEquals(List.of("t1", "t2", "t3"), titles(out));
    }

    @Test
    @DisplayName("rerank: 상위 RR_OUTPUT_MAX(6)개로 자른다")
    void rerankCapsOutput() {
        QueryPipeline p = pipelineWithLlm((s, u) -> "[8,7,6,5,4,3,2,1]");
        List<Memory> in = List.of(mem(1), mem(2), mem(3), mem(4), mem(5), mem(6), mem(7), mem(8));
        List<Memory> out = p.rerank("q", in);
        assertEquals(6, out.size());
        assertEquals("t8", out.get(0).getTitle());
    }

    @Test
    @DisplayName("rerank: 후보 1개면 LLM 없이 그대로")
    void rerankSkipsForSingle() {
        QueryPipeline p = pipelineWithLlm((s, u) -> "[1]");
        List<Memory> in = List.of(mem(1));
        assertEquals(in, p.rerank("q", in));
    }
}
