package com.recall.memory.type.troubleshooting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.LlmJson;
import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.ExtractionStrategy;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 트러블슈팅(troubleshooting) 유형 S2 추출 전략. 마스킹된 원문을 LLM 포트로 troubleshooting 스키마({@link
 * TroubleshootingCard})로 구조화한다.
 *
 * <p>LLM 응답 파싱·매핑·실패 시 fallback은 <b>결정론</b>이라 단위테스트로 검증한다(확률적인 LLM 호출 자체는 포트 뒤로 격리). 실제 provider가
 * 없으면 {@code StubLlmClient}가 placeholder를 반환하고, 그 경우 원문을 보존한 fallback 카드로 흐름을 이어간다(조용한 실패 금지 — 로그로
 * 드러냄).
 *
 * <p>fallback 카드는 <b>해결됐다고 말하지 않는다</b> — status는 UNRESOLVED, root_cause·final_solution은 빈 값으로 두고
 * 증상·요약에 원문을 남긴다(근거 없는 생성 금지).
 */
@Component
public class TroubleshootingExtraction implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(TroubleshootingExtraction.class);

    /** LLM이 실패/미연동일 때 fallback 제목에 쓸 원문 앞부분 최대 길이. */
    private static final int TITLE_FALLBACK_MAX = 60;

    /** LLM 응답 파싱 실패 로그에 남길 응답 미리보기 최대 길이. */
    private static final int PREVIEW_MAX = 120;

    /** S2 추출 시스템 프롬프트 리소스 경로(코드가 아니라 콘텐츠라 파일로 분리). */
    private static final String PROMPT_PATH = "prompts/troubleshooting-extraction.md";

    private final String systemPrompt;

    // 이 앱은 주입 가능한 ObjectMapper 빈이 없어 코드베이스 관례대로 내부에서 생성한다(KnowledgeExtraction과 동일).
    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TroubleshootingExtraction(PromptLoader promptLoader) {
        this.systemPrompt = promptLoader.load(PROMPT_PATH);
    }

    @Override
    public MemoryType supports() {
        return MemoryType.TROUBLESHOOTING;
    }

    @Override
    public Map<String, Object> extract(String maskedText, UserAiContext ctx) {
        TroubleshootingCard card = extractCard(maskedText, ctx.requireChat());
        return objectMapper.convertValue(card, new TypeReference<Map<String, Object>>() {});
    }

    private TroubleshootingCard extractCard(String maskedText, LlmClient llmClient) {
        String raw;
        try {
            raw = llmClient.complete(systemPrompt, maskedText);
        } catch (RuntimeException e) {
            log.warn("LLM 추출 호출 실패 → fallback: {}", e.getMessage());
            return fallback(maskedText);
        }

        String json = LlmJson.extractObject(raw);
        if (json == null) {
            log.warn("LLM 응답에서 JSON을 찾지 못함 → fallback (응답: {})", preview(raw));
            return fallback(maskedText);
        }
        try {
            TroubleshootingCard parsed = objectMapper.readValue(json, TroubleshootingCard.class);
            // 제목이 비면 승인 시 memory.title이 비므로 원문에서 파생해 보장한다.
            return parsed.title().isBlank() ? withTitle(parsed, deriveTitle(maskedText)) : parsed;
        } catch (JsonProcessingException e) {
            log.warn("LLM 응답 JSON 파싱 실패 → fallback: {}", e.getMessage());
            return fallback(maskedText);
        }
    }

    /** 원문을 유실하지 않는 최소 카드 — 제목은 원문 앞부분, 요약·증상은 원문 그대로. 원인·해결은 <b>비워 둔다</b>(모르는 것을 채우지 않는다). */
    private TroubleshootingCard fallback(String maskedText) {
        return new TroubleshootingCard(
                deriveTitle(maskedText),
                maskedText,
                List.of(),
                maskedText,
                "",
                "",
                "",
                List.of(),
                "",
                "",
                TroubleshootingCard.UNRESOLVED);
    }

    private TroubleshootingCard withTitle(TroubleshootingCard card, String title) {
        return new TroubleshootingCard(
                title,
                card.summary(),
                card.keywords(),
                card.symptom(),
                card.errorMessage(),
                card.errorSignature(),
                card.environment(),
                card.attempts(),
                card.rootCause(),
                card.finalSolution(),
                card.status());
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
}
