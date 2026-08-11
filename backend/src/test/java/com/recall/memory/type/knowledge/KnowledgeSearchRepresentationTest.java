package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.common.MemoryType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeSearchRepresentationTest {

    private final KnowledgeSearchRepresentation rep = new KnowledgeSearchRepresentation();

    @Test
    @DisplayName("supports()는 KNOWLEDGE")
    void supports() {
        assertEquals(MemoryType.KNOWLEDGE, rep.supports());
    }

    @Test
    @DisplayName("document와 facts를 각 kind로 낸다")
    void documentAndFacts() {
        Map<String, String> texts =
                rep.embeddingTexts(Map.of("document", "본문 내용", "facts", List.of("사실 1", "사실 2")));
        assertEquals("본문 내용", texts.get("document"));
        assertEquals("사실 1\n사실 2", texts.get("fact"));
    }

    @Test
    @DisplayName("document가 비면 summary로 폴백한다")
    void documentFallsBackToSummary() {
        Map<String, String> texts = rep.embeddingTexts(Map.of("summary", "요약만 있음", "document", ""));
        assertEquals("요약만 있음", texts.get("document"));
    }

    @Test
    @DisplayName("빈 값 kind는 생략한다")
    void skipsEmptyKinds() {
        Map<String, String> texts =
                rep.embeddingTexts(Map.of("document", "본문", "facts", List.of()));
        assertTrue(texts.containsKey("document"));
        assertFalse(texts.containsKey("fact"));
    }

    @Test
    @DisplayName("document·summary 모두 없으면 document kind도 없다")
    void noDocumentWhenAllEmpty() {
        Map<String, String> texts = rep.embeddingTexts(Map.of("facts", List.of("사실")));
        assertFalse(texts.containsKey("document"));
        assertEquals("사실", texts.get("fact"));
    }
}
