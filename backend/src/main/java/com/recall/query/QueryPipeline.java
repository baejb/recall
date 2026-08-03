package com.recall.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.type.AnswerContribution;
import com.recall.query.dto.AnswerFragment;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 파이프라인: 질문 → 분류(C) → 검색(P·R·W·RR) → 답변(A). 답변은 저장된 근거(memory)에 매이고, 근거가 없으면 지어내지 않고 빈 결과(상위가
 * "기록 없음")를 낸다.
 *
 * <p>흐름 검증용 STUB: 분류·검색은 단순화(기본 유형 + DB 조회). Phase 1에서 다차원 분류·하이브리드 검색·리랭크로 채운다.
 */
@Component
public class QueryPipeline {

    private final MemoryRepository memoryRepository;
    private final StrategyRegistry<AnswerContribution> answers;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryPipeline(
            MemoryRepository memoryRepository, List<AnswerContribution> answerContributions) {
        this.memoryRepository = memoryRepository;
        this.answers = new StrategyRegistry<>(answerContributions);
    }

    @Transactional(readOnly = true)
    public List<AnswerFragment> answer(String question) {
        MemoryType type = classify(question); // C
        List<Memory> candidates = search(type); // P·R·W·RR
        return candidates.stream()
                .map(
                        m ->
                                new AnswerFragment(
                                        answers.get(m.getType()).render(parse(m.getStructured())),
                                        m.getId()))
                .toList(); // A — 근거(memory id)와 함께
    }

    private MemoryType classify(String question) {
        // TODO(Phase 1): 다차원 분류(C). 지금은 기본 KNOWLEDGE.
        return MemoryType.KNOWLEDGE;
    }

    private List<Memory> search(MemoryType type) {
        // TODO(Phase 1): 하이브리드 검색(exact·bm25·vector) + 가중치·리랭크. 지금은 유형별 활성 카드 조회.
        return memoryRepository.findByTypeAndStatus(type, "active");
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("structured 파싱 실패", e);
        }
    }
}
