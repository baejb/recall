package com.recall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recall.capture.Capture;
import com.recall.capture.CaptureRepository;
import com.recall.common.MemoryType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * memory 상세 조회(Unit O1) 통합. structured JSON을 파싱해 summary/keywords/facts/document를 펼치는지, 없는 id는
 * 404인지, 필드가 일부 빠진 structured도 500 없이 방어적으로 처리하는지를 본다. 공유 테이블을 쓰므로 만든 행은 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryDetailFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CaptureRepository captureRepository;
    @Autowired private MemoryRepository memoryRepository;

    private final List<Long> createdMemories = new ArrayList<>();
    private final List<Long> createdCaptures = new ArrayList<>();

    @AfterEach
    void cleanup() {
        memoryRepository.deleteAllById(createdMemories);
        captureRepository.deleteAllById(createdCaptures);
        createdMemories.clear();
        createdCaptures.clear();
    }

    @Test
    void detailReturnsParsedStructuredFields() throws Exception {
        String structured =
                """
                {
                  "title": "N+1 문제 해결",
                  "summary": "지연 로딩 대신 fetch join 사용",
                  "keywords": ["jpa", "n+1", "fetch join"],
                  "facts": ["LAZY 연관관계는 개별 쿼리를 유발한다", "fetch join으로 한 번에 로딩 가능"],
                  "document": "N+1 문제는 ..."
                }
                """;
        Memory memory = seedMemory("N+1 문제 해결", structured);

        mockMvc.perform(get("/api/memories/{id}", memory.getId()))
                .andExpect(status().isOk())
                .andExpect(
                        result -> {
                            String json = result.getResponse().getContentAsString();
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .readTree(json);
                            assertEquals(memory.getId(), node.get("id").asLong());
                            assertEquals(
                                    memory.getCapture().getId(), node.get("captureId").asLong());
                            assertEquals("KNOWLEDGE", node.get("type").asText());
                            assertEquals("N+1 문제 해결", node.get("title").asText());
                            assertEquals("지연 로딩 대신 fetch join 사용", node.get("summary").asText());
                            assertEquals("N+1 문제는 ...", node.get("document").asText());
                            assertEquals("active", node.get("status").asText());
                            List<String> keywords = new ArrayList<>();
                            node.get("keywords").forEach(n -> keywords.add(n.asText()));
                            assertEquals(List.of("jpa", "n+1", "fetch join"), keywords);
                            List<String> facts = new ArrayList<>();
                            node.get("facts").forEach(n -> facts.add(n.asText()));
                            assertEquals(2, facts.size());
                            assertEquals("LAZY 연관관계는 개별 쿼리를 유발한다", facts.get(0));
                        });
    }

    @Test
    void detailOfMissingMemoryReturns404() throws Exception {
        long missingId = maxMemoryId() + 1000;

        mockMvc.perform(get("/api/memories/{id}", missingId)).andExpect(status().isNotFound());
    }

    @Test
    void detailToleratesMissingStructuredFields() throws Exception {
        String structured =
                """
                { "title": "제목만 있음" }
                """;
        Memory memory = seedMemory("제목만 있음", structured);

        mockMvc.perform(get("/api/memories/{id}", memory.getId()))
                .andExpect(status().isOk())
                .andExpect(
                        result -> {
                            String json = result.getResponse().getContentAsString();
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .readTree(json);
                            assertEquals("제목만 있음", node.get("title").asText());
                            assertTrue(node.get("summary").isNull());
                            assertTrue(node.get("document").isNull());
                            assertTrue(node.get("keywords").isArray());
                            assertEquals(0, node.get("keywords").size());
                            assertTrue(node.get("facts").isArray());
                            assertEquals(0, node.get("facts").size());
                        });
    }

    private Memory seedMemory(String title, String structuredJson) {
        Capture capture = captureRepository.save(new Capture(1L, "chat", "마스킹된 원문", "[]"));
        createdCaptures.add(capture.getId());
        Memory memory =
                memoryRepository.save(
                        new Memory(capture, MemoryType.KNOWLEDGE, title, structuredJson));
        createdMemories.add(memory.getId());
        return memory;
    }

    private long maxMemoryId() {
        return memoryRepository.findAll().stream().mapToLong(Memory::getId).max().orElse(0L);
    }
}
