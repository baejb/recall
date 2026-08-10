package com.recall.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.memory.Memory;
import com.recall.memory.type.AnswerContribution;
import com.recall.query.dto.AnswerFragment;
import com.recall.search.HybridSearchService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 파이프라인: 질문 → 분류(C) → 하이브리드 검색(R·W) → 답변(A). 답변은 저장된 근거(memory)에 매이고, 근거가 없으면 지어내지 않고 빈 결과(상위가
 * "기록 없음")를 낸다.
 *
 * <p>분류(C)는 아직 stub(기본 KNOWLEDGE), 검색은 vector+BM25 융합. 리랭크(RR)·다차원 분류는 후속.
 */
@Component
public class QueryPipeline {

    private final HybridSearchService searchService;
    private final StrategyRegistry<AnswerContribution> answers;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryPipeline(
            HybridSearchService searchService, List<AnswerContribution> answerContributions) {
        this.searchService = searchService;
        this.answers = new StrategyRegistry<>(answerContributions);
    }

    @Transactional(readOnly = true)
    public List<AnswerFragment> answer(String question) {
        MemoryType type = classify(question); // C
        List<Memory> candidates = searchService.search(question, type); // R·W
        return candidates.stream().map(this::toFragment).toList(); // A — 근거(memory id)와 함께
    }

    /** memory 한 건을 유형별 답변 전략으로 렌더링해 근거(memory id)와 함께 조각으로 만든다. */
    private AnswerFragment toFragment(Memory memory) {
        String rendered = answers.get(memory.getType()).render(parse(memory.getStructured()));
        return new AnswerFragment(rendered, memory.getId());
    }

    private MemoryType classify(String question) {
        // TODO(Phase 1): 다차원 분류(C). 지금은 기본 KNOWLEDGE.
        return MemoryType.KNOWLEDGE;
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("structured 파싱 실패", e);
        }
    }
}
