package com.recall.memory.type.knowledge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.common.PromptLoader;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.SimilarityJudgeStrategy;
import com.recall.memory.type.Verdict;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 지식(knowledge) 유형 S4 판정 — 신규 후보(proposed)와 유사 기존 기억(existing)의 사실을 LLM으로 대조해
 * NEW/RECURRENCE/SUPPLEMENT/CONFLICT를 가린다.
 *
 * <p>LLM 응답 파싱·fallback은 결정론이라 단위테스트로 검증한다. 실제 provider가 없으면 stub이 고정 문자열을 주므로 파싱 실패 → fallback으로
 * 안전하게 흐른다(조용한 실패 금지 — 로그로 드러냄). targetMemoryId는 여기서 알 수 없어 null로 두고 저장 파이프라인이 후보 id로 채운다.
 */
@Component
public class KnowledgeJudge implements SimilarityJudgeStrategy {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeJudge.class);

    /** S4 판정 시스템 프롬프트 리소스 경로(코드가 아니라 콘텐츠라 파일로 분리). */
    private static final String PROMPT_PATH = "prompts/knowledge-judgement.md";

    private final String systemPrompt;

    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public KnowledgeJudge(PromptLoader promptLoader) {
        this.systemPrompt = promptLoader.load(PROMPT_PATH);
    }

    @Override
    public MemoryType supports() {
        return MemoryType.KNOWLEDGE;
    }

    @Override
    public Judgement judge(
            Map<String, Object> proposed, Map<String, Object> existing, UserAiContext ctx) {
        if (existing == null || existing.isEmpty()) {
            // 유사 후보 자체가 없으면 LLM을 부를 필요가 없다 — ctx.requireChat()도 호출하지 않는다.
            return new Judgement(Verdict.NEW, null, "유사한 기존 기억 없음");
        }
        LlmClient llmClient = ctx.requireChat();
        String userPrompt = toUserPrompt(proposed, existing);
        String raw;
        try {
            raw = llmClient.complete(systemPrompt, userPrompt);
        } catch (RuntimeException e) {
            log.warn("LLM 판정 호출 실패 → fallback: {}", e.getMessage());
            return fallback();
        }
        return parseJudgement(raw);
    }

    private Judgement parseJudgement(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("LLM 판정 응답에서 JSON을 찾지 못함 → fallback");
            return fallback();
        }
        try {
            JudgeResult result = objectMapper.readValue(json, JudgeResult.class);
            Verdict verdict = toVerdict(result.verdict());
            if (verdict == null) {
                log.warn("알 수 없는 verdict '{}' → fallback", result.verdict());
                return fallback();
            }
            String rationale =
                    result.rationale() == null || result.rationale().isBlank()
                            ? "(근거 없음)"
                            : result.rationale();
            return new Judgement(verdict, null, rationale);
        } catch (Exception e) {
            log.warn("LLM 판정 응답 파싱 실패 → fallback: {}", e.getMessage());
            return fallback();
        }
    }

    /** 후보는 있으나 판정이 불확실한 경우 — NEW로 새로 만들지 않고 사람 검토를 유도한다(자동 덮어쓰기 금지). */
    private Judgement fallback() {
        return new Judgement(Verdict.SUPPLEMENT, null, "자동 판정 실패 — 사람 검토 필요");
    }

    private Verdict toVerdict(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Verdict.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String toUserPrompt(Map<String, Object> proposed, Map<String, Object> existing) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("proposed", proposed, "existing", existing));
        } catch (Exception e) {
            throw new IllegalStateException("판정 입력 직렬화 실패", e);
        }
    }

    /** 응답 텍스트에서 첫 '{'~마지막 '}' 구간만 잘라 JSON 본문 후보를 얻는다(산문/마크다운으로 감싸도 견딤). */
    private String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }

    /** LLM 판정 응답(필요 필드만). */
    private record JudgeResult(String verdict, String rationale) {}
}
