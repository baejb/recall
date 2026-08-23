package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.memory.type.EmbeddingKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R(검색 표현) knowledge — "무엇을 임베딩하나"는 결정론이라 kind 구성·폴백·빈 값 생략을 단위테스트로 고정한다. */
class KnowledgeSearchRepresentationTest {

    private final KnowledgeSearchRepresentation rep = new KnowledgeSearchRepresentation();

    /** kind 이름은 EmbeddingKind 상수로 확인한다 — 테스트가 문자열을 복사해 두면 프로덕션 어휘 변경을 못 잡는다. */
    private static KnowledgeCard card(String summary, String document, List<String> facts) {
        return new KnowledgeCard("제목", summary, List.of(), facts, document);
    }

    @Test
    @DisplayName("document와 facts를 각 kind로 낸다")
    void documentAndFacts() {
        Map<String, String> texts = rep.embeddingTexts(card("", "본문 내용", List.of("사실 1", "사실 2")));
        assertEquals("본문 내용", texts.get(EmbeddingKind.DOCUMENT));
        assertEquals("사실 1\n사실 2", texts.get(EmbeddingKind.FACT));
    }

    @Test
    @DisplayName("document가 비면 summary로 폴백한다")
    void documentFallsBackToSummary() {
        Map<String, String> texts = rep.embeddingTexts(card("요약만 있음", "", List.of()));
        assertEquals("요약만 있음", texts.get(EmbeddingKind.DOCUMENT));
    }

    @Test
    @DisplayName("빈 값 kind는 생략한다")
    void skipsEmptyKinds() {
        Map<String, String> texts = rep.embeddingTexts(card("", "본문", List.of()));
        assertTrue(texts.containsKey(EmbeddingKind.DOCUMENT));
        assertFalse(texts.containsKey(EmbeddingKind.FACT));
    }

    @Test
    @DisplayName("document·summary 모두 없으면 document kind도 없다")
    void noDocumentWhenAllEmpty() {
        Map<String, String> texts = rep.embeddingTexts(card("", "", List.of("사실")));
        assertFalse(texts.containsKey(EmbeddingKind.DOCUMENT));
        assertEquals("사실", texts.get(EmbeddingKind.FACT));
    }
}
