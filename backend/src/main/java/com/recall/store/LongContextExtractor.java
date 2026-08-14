package com.recall.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.common.StrategyRegistry;
import com.recall.llm.LlmClient;
import com.recall.memory.type.ExtractionStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * S3 — 긴맥락 Map-Reduce. 짧은 원문은 S2({@link ExtractionStrategy})로 단일 패스, 긴 원문은 조각내어(🟢 결정론 청킹) 조각마다 S2
 * 추출(🔵 Map) 후 부분 카드들을 하나로 병합(🔵 Reduce)한다.
 *
 * <p>불변 원칙: <b>조용한 truncation 금지</b> — 청킹은 원문 전체를 겹침 윈도우로 커버해 어느 부분도 버리지 않는다. <b>마스킹 우선</b> — 입력은
 * 이미 M0 마스킹을 거친 원문이고 조각도 그 부분문자열이라 시크릿이 새지 않는다. Reduce LLM이 미가용/실패하면 결정론 병합(리스트 union)으로 격하해 사실을
 * 유실하지 않는다(조용한 실패 금지).
 */
@Component
public class LongContextExtractor {

    private static final Logger log = LoggerFactory.getLogger(LongContextExtractor.class);

    /** 이 길이 이하는 단일 패스(S2 직행) — 토큰 예산 분기(PRD: ≤8k 토큰 단일패스의 문자 근사). */
    static final int SINGLE_PASS_MAX_CHARS = 12_000;

    /** 조각 크기(문자). */
    static final int CHUNK_CHARS = 8_000;

    /** 조각 간 겹침(문자) — 경계에서 사실이 잘려 유실되는 것을 완화. */
    static final int OVERLAP_CHARS = 500;

    private static final String MERGE_PROMPT_PATH = "prompts/longcontext-merge.md";

    private final StrategyRegistry<ExtractionStrategy> extractions;
    private final LlmClient llmClient;
    private final String mergeSystemPrompt;
    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LongContextExtractor(
            List<ExtractionStrategy> extractionStrategies,
            LlmClient llmClient,
            PromptLoader promptLoader) {
        this.extractions = new StrategyRegistry<>(extractionStrategies);
        this.llmClient = llmClient;
        this.mergeSystemPrompt = promptLoader.load(MERGE_PROMPT_PATH);
    }

    /**
     * 유형별 구조화 추출(S2/S3). 짧으면 단일 패스, 길면 청킹 후 Map-Reduce. 반환은 S2와 동일한 {@code Map<String,Object>} 카드라
     * 이후 판정(S4)·검토 흐름은 그대로다.
     */
    public Map<String, Object> extract(MemoryType type, String maskedText) {
        ExtractionStrategy strategy = extractions.get(type);
        if (maskedText == null || maskedText.length() <= SINGLE_PASS_MAX_CHARS) {
            return strategy.extract(maskedText); // 단일 패스(S2)
        }
        List<String> chunks = chunk(maskedText, CHUNK_CHARS, OVERLAP_CHARS);
        log.info("S3 긴맥락 추출: {}자 → {}조각 Map-Reduce", maskedText.length(), chunks.size());
        List<Map<String, Object>> partials =
                chunks.stream().map(strategy::extract).toList(); // Map — 조각마다 S2
        return reduce(partials); // Reduce
    }

    /** 부분 카드들을 하나로 병합(Reduce). LLM 병합을 우선하고, 미가용/실패/파싱불가면 결정론 병합으로 격하한다. */
    private Map<String, Object> reduce(List<Map<String, Object>> partials) {
        if (partials.size() == 1) {
            return partials.get(0);
        }
        if (!llmClient.available()) {
            return mergeDeterministic(partials); // stub → 결정론 병합
        }
        try {
            String raw = llmClient.complete(mergeSystemPrompt, toJson(partials));
            Map<String, Object> merged = parseCard(raw);
            if (merged == null || merged.isEmpty()) {
                log.warn("S3 병합 응답 파싱 실패 → 결정론 병합");
                return mergeDeterministic(partials);
            }
            return merged;
        } catch (RuntimeException e) {
            log.warn("S3 병합 실패 → 결정론 병합: {}", e.getMessage());
            return mergeDeterministic(partials);
        }
    }

    /** 원문을 겹침 윈도우로 조각낸다(🟢 결정론). 마지막 조각은 원문 끝까지 도달해 <b>전체를 커버</b>한다(truncation 없음). 같은 입력=같은 조각. */
    static List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int step = Math.max(1, size - overlap); // overlap ≥ size 방어(무한 루프 금지)
        int i = 0;
        while (i < len) {
            int end = Math.min(i + size, len);
            chunks.add(text.substring(i, end));
            if (end >= len) {
                break;
            }
            i += step;
        }
        return chunks;
    }

    /** 결정론 병합(격하): 리스트 필드는 중복 제거 union(사실 유실 방지), 스칼라 필드는 첫 non-blank 값. 스키마 무관하게 동작한다. */
    static Map<String, Object> mergeDeterministic(List<Map<String, Object>> partials) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> partial : partials) {
            for (Map.Entry<String, Object> e : partial.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (value instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    List<Object> acc =
                            (List<Object>) result.computeIfAbsent(key, k -> new ArrayList<>());
                    for (Object item : list) {
                        if (!acc.contains(item)) {
                            acc.add(item);
                        }
                    }
                } else if (value != null && !result.containsKey(key)) {
                    boolean blank = value instanceof String s && s.isBlank();
                    if (!blank) {
                        result.put(key, value); // 첫 non-blank 스칼라
                    }
                }
            }
        }
        return result;
    }

    private String toJson(List<Map<String, Object>> partials) {
        try {
            return objectMapper.writeValueAsString(partials);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("부분 카드 직렬화 실패", e);
        }
    }

    /** LLM 병합 응답에서 첫 '{'~마지막 '}'를 잘라 Map으로 파싱한다. 실패하면 null. */
    private Map<String, Object> parseCard(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    raw.substring(start, end + 1), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
