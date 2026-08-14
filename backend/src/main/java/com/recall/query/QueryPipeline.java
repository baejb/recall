package com.recall.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.llm.LlmClient;
import com.recall.memory.Memory;
import com.recall.memory.type.AnswerContribution;
import com.recall.query.dto.AnswerFragment;
import com.recall.search.HybridSearchService;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 파이프라인: 질문 → 분류(C) → 하이브리드 검색(R·W) → 답변(A). 답변은 저장된 근거(memory)에 매이고, 근거가 없으면 지어내지 않고 빈 결과(상위가
 * "기록 없음")를 낸다.
 *
 * <p>분류(C)는 아직 stub(기본 KNOWLEDGE), 검색(R·W)은 vector+BM25 융합(결정론). 답변(A)은 근거만으로 LLM이 재구성해 토큰 단위로
 * 스트리밍한다. LLM이 미가용/실패면 결정론 폴백(각 근거의 요약)으로 격하한다(조용한 실패 금지 · 근거 없는 생성 금지).
 */
@Component
public class QueryPipeline {

    /** A(답변) 그라운딩 시스템 프롬프트 — 근거에 매인 답만 허용(근거 없는 생성 금지). */
    static final String ANSWER_SYSTEM =
            """
            너는 Recall의 답변 작성기다. 아래 '근거'에 담긴 내용만으로 사용자의 질문에 답한다.
            - 근거에 없는 사실·수치·결론은 절대 지어내지 않는다.
            - 근거가 질문에 답하기 부족하면 "기록 없음"이라고만 답한다.
            - 근거를 그대로 나열하지 말고, 질문에 맞게 간결한 한국어로 재구성한다.
            """;

    private final HybridSearchService searchService;
    private final StrategyRegistry<AnswerContribution> answers;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryPipeline(
            HybridSearchService searchService,
            List<AnswerContribution> answerContributions,
            LlmClient llmClient) {
        this.searchService = searchService;
        this.answers = new StrategyRegistry<>(answerContributions);
        this.llmClient = llmClient;
    }

    /**
     * 분류(C) + 하이브리드 검색(R·W) → 근거 후보. 트랜잭션은 여기까지만 잡고(느린 LLM 호출은 트랜잭션 밖), 이후 답변 합성은 로드된
     * memory(structured 컬럼)만 사용해 커넥션을 오래 점유하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Memory> retrieve(String question) {
        MemoryType type = classify(question); // C
        return searchService.search(question, type); // R·W
    }

    /** 실제 LLM(비-stub)이 연동됐는지 — 답변 경로가 LLM 합성 vs 결정론 폴백을 고른다. */
    public boolean llmReady() {
        return llmClient.available();
    }

    /** 근거만으로 답을 합성해 토큰을 {@code onToken}으로 흘린다(A, 스트리밍). LLM 실패는 예외로 드러낸다 — 호출부가 폴백을 결정한다. */
    public void composeStreaming(
            String question, List<Memory> candidates, Consumer<String> onToken) {
        llmClient.completeStream(
                ANSWER_SYSTEM, buildEvidencePrompt(question, candidates, objectMapper), onToken);
    }

    /** 격하(LLM 미가용/실패): 각 근거를 유형별 전략으로 렌더(요약)해 근거(memory id)와 함께 조각으로 낸다 — 나열이지만 근거에 매여 있다. */
    public List<AnswerFragment> fallbackFragments(List<Memory> candidates) {
        return candidates.stream()
                .map(
                        m ->
                                new AnswerFragment(
                                        answers.get(m.getType()).render(parse(m.getStructured())),
                                        m.getId()))
                .toList();
    }

    /** 질문 + 번호 매긴 근거(제목·요약·사실)로 LLM 사용자 프롬프트를 만든다. 근거 콘텐츠는 마스킹된 원문에서 추출된 것이다. */
    static String buildEvidencePrompt(
            String question, List<Memory> candidates, ObjectMapper mapper) {
        StringBuilder sb = new StringBuilder();
        sb.append("질문: ").append(question).append("\n\n근거:\n");
        int n = 1;
        for (Memory m : candidates) {
            Map<String, Object> s = parseWith(mapper, m.getStructured());
            sb.append('[').append(n++).append("] ");
            Object title = s.get("title");
            if (title != null) {
                sb.append(title).append(" — ");
            }
            Object summary = s.get("summary");
            if (summary != null) {
                sb.append(summary);
            }
            if (s.get("facts") instanceof List<?> facts && !facts.isEmpty()) {
                sb.append("\n    사실: ");
                for (int i = 0; i < facts.size(); i++) {
                    if (i > 0) {
                        sb.append("; ");
                    }
                    sb.append(String.valueOf(facts.get(i)));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private MemoryType classify(String question) {
        // TODO(Phase 1): 다차원 분류(C). 지금은 기본 KNOWLEDGE.
        return MemoryType.KNOWLEDGE;
    }

    private Map<String, Object> parse(String json) {
        return parseWith(objectMapper, json);
    }

    private static Map<String, Object> parseWith(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("structured 파싱 실패", e);
        }
    }
}
