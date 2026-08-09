package com.recall.memory.type.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.llm.LlmClient;
import com.recall.memory.type.ExtractionStrategy;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 지식(knowledge) 유형 S2 추출 전략. 마스킹된 원문을 LLM 포트로 knowledge 스키마 ({@link KnowledgeCard})로 구조화한다.
 *
 * <p>LLM 응답 파싱·매핑·실패 시 fallback은 <b>결정론</b>이라 단위테스트로 검증한다(확률적인 LLM 호출 자체는 포트 뒤로 격리). 실제 provider
 * 어댑터가 없으면 {@code StubLlmClient}가 placeholder를 반환하고, 그 경우 fallback 카드로 흐름을 이어간다(조용한 실패 금지 — 로그로
 * 드러냄, PRD "실패 시도 버리지 말 것").
 */
@Component
public class KnowledgeExtraction implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtraction.class);

    /** LLM이 실패/미연동일 때 fallback 제목에 쓸 원문 앞부분 최대 길이. */
    private static final int TITLE_FALLBACK_MAX = 60;

    /** LLM 응답 파싱 실패 로그에 남길 원문 미리보기 최대 길이. */
    private static final int PREVIEW_MAX = 120;

    private static final String SYSTEM_PROMPT =
            """
            너는 개발자의 메모에서 '지식 카드'를 뽑아내는 추출기다.
            입력 텍스트를 분석해 아래 JSON 스키마로만 응답하라. JSON 외 다른 텍스트는 절대 출력하지 마라.
            {
              "title":    "한 줄 제목(핵심 주제)",
              "summary":  "2~3문장 요약",
              "keywords": ["핵심 키워드", ...],
              "facts":    ["검증 가능한 사실 진술", ...],
              "document": "정리된 본문(원문의 지식을 재구성)"
            }
            사실이 아닌 것을 지어내지 말고, 근거가 없으면 해당 배열은 비워라.
            """;

    private final LlmClient llmClient;

    // 이 앱은 주입 가능한 ObjectMapper 빈이 없어 코드베이스 관례대로 내부에서 생성한다(StorePipeline·ReviewService와 동일).
    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public KnowledgeExtraction(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public Map<String, Object> extract(String maskedText) {
        KnowledgeCard card = extractCard(maskedText);
        return objectMapper.convertValue(card, new TypeReference<Map<String, Object>>() {});
    }

    private KnowledgeCard extractCard(String maskedText) {
        String raw;
        try {
            raw = llmClient.complete(SYSTEM_PROMPT, maskedText);
        } catch (RuntimeException e) {
            log.warn("LLM 추출 호출 실패 → fallback: {}", e.getMessage());
            return fallback(maskedText);
        }

        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("LLM 응답에서 JSON을 찾지 못함 → fallback (응답: {})", preview(raw));
            return fallback(maskedText);
        }
        try {
            KnowledgeCard parsed = objectMapper.readValue(json, KnowledgeCard.class);
            // 제목이 비면 승인 시 memory.title이 비므로 원문에서 파생해 보장한다.
            return isBlank(parsed.title()) ? withTitle(parsed, deriveTitle(maskedText)) : parsed;
        } catch (JsonProcessingException e) {
            log.warn("LLM 응답 JSON 파싱 실패 → fallback: {}", e.getMessage());
            return fallback(maskedText);
        }
    }

    /** 원문을 유실하지 않는 최소 카드 — 제목은 원문 앞부분, 요약·본문은 원문 그대로. */
    private KnowledgeCard fallback(String maskedText) {
        return new KnowledgeCard(
                deriveTitle(maskedText), maskedText, List.of(), List.of(), maskedText);
    }

    private KnowledgeCard withTitle(KnowledgeCard card, String title) {
        return new KnowledgeCard(
                title, card.summary(), card.keywords(), card.facts(), card.document());
    }

    /** 응답 텍스트에서 첫 '{'~마지막 '}' 구간만 잘라 JSON 본문 후보를 얻는다(모델이 산문/마크다운으로 감싸도 견딤). */
    private String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }

    private String deriveTitle(String maskedText) {
        String text = maskedText == null ? "" : maskedText.strip();
        if (text.isEmpty()) {
            return "(제목 없음)";
        }
        return text.length() <= TITLE_FALLBACK_MAX
                ? text
                : text.substring(0, TITLE_FALLBACK_MAX).strip() + "…";
    }

    private String preview(String raw) {
        if (raw == null) {
            return "(null)";
        }
        return raw.length() <= PREVIEW_MAX ? raw : raw.substring(0, PREVIEW_MAX) + "…";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
