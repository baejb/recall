package com.recall.memory.type.knowledge;

import com.recall.common.MemoryType;
import com.recall.memory.type.SearchRepresentation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 지식(knowledge) 유형 검색 표현(R) — 무엇을 임베딩할지 정한다. KnowledgeCard의 {@code document}(비면 summary로 폴백)를
 * {@code kind="document"}, {@code facts}를 합쳐 {@code kind="fact"}로 낸다. 빈 값 kind는 생략한다.
 */
@Component
public class KnowledgeSearchRepresentation implements SearchRepresentation {

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public Map<String, String> embeddingTexts(Map<String, Object> structured) {
        Map<String, String> texts = new LinkedHashMap<>();

        String document = str(structured.get("document"));
        if (document.isBlank()) {
            document = str(structured.get("summary"));
        }
        if (!document.isBlank()) {
            texts.put("document", document);
        }

        String facts = joinFacts(structured.get("facts"));
        if (!facts.isBlank()) {
            texts.put("fact", facts);
        }
        return texts;
    }

    private String joinFacts(Object facts) {
        if (!(facts instanceof List<?> list)) {
            return "";
        }
        return list.stream()
                .map(KnowledgeSearchRepresentation::str)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }
}
