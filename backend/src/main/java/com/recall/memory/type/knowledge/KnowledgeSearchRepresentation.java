package com.recall.memory.type.knowledge;

import com.recall.common.type.MemoryType;
import com.recall.memory.type.EmbeddingKind;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SearchRepresentation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 지식(knowledge) 유형 검색 표현(R) — 무엇을 임베딩할지 정한다. 카드의 {@code document}(비면 summary로 폴백)를 {@link
 * EmbeddingKind#DOCUMENT}, {@code facts}를 합쳐 {@link EmbeddingKind#FACT}로 낸다. 빈 값 kind는 생략한다.
 */
@Component
public class KnowledgeSearchRepresentation implements SearchRepresentation {

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public Map<String, String> embeddingTexts(MemoryCard card) {
        KnowledgeCard kn = requireKnowledge(card);
        Map<String, String> texts = new LinkedHashMap<>();

        String document = kn.document().isBlank() ? kn.summary() : kn.document();
        if (!document.isBlank()) {
            texts.put(EmbeddingKind.DOCUMENT, document);
        }

        String facts = joinFacts(kn);
        if (!facts.isBlank()) {
            texts.put(EmbeddingKind.FACT, facts);
        }
        return texts;
    }

    private static KnowledgeCard requireKnowledge(MemoryCard card) {
        if (card instanceof KnowledgeCard kn) {
            return kn;
        }
        throw new IllegalArgumentException(
                "KNOWLEDGE 전략에 다른 유형 카드가 전달됨: "
                        + (card == null ? "null" : card.getClass().getSimpleName()));
    }

    private String joinFacts(KnowledgeCard kn) {
        return kn.facts().stream()
                .map(fact -> fact == null ? "" : fact.strip())
                .filter(fact -> !fact.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
