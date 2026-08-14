package com.recall.query;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.memory.Memory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A(답변) 그라운딩 프롬프트 — 질문과 근거(제목·요약·사실)가 LLM 입력에 들어가는지(근거에 매인 답의 전제). */
class QueryPipelineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("프롬프트에 질문 + 번호 매긴 근거의 제목·요약·사실이 담긴다")
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
}
