package com.recall.memory.type.troubleshooting;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.LlmJson;
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
 * 트러블슈팅(troubleshooting) 유형 S4 판정 — 신규 후보(proposed)와 유사 기존 기억(existing)을 LLM으로 대조해
 * NEW/RECURRENCE/SUPPLEMENT/CONFLICT를 가린다.
 *
 * <p>지식 유형과 판정 근거가 다르다: 표면 유사도가 아니라 <b>error_signature·root_cause·environment·final_solution</b>을
 * 대조한다(증상이 비슷해도 원인이 다르면 다른 문제, 같은 에러라도 환경이 다르면 재발이 아닐 수 있다). 그 프롬프트는 {@code
 * prompts/troubleshooting-judgement.md}에 있다.
 *
 * <p>LLM 응답 파싱·fallback은 결정론이라 단위테스트로 검증한다. targetMemoryId는 여기서 알 수 없어 null로 두고 저장 파이프라인이 후보 id로
 * 채운다(판정=유형별 관심사, id 배선=파이프라인 관심사).
 *
 * <p><b>자동 덮어쓰기 금지</b>: 이 전략은 판정만 하고 기존 기록을 고치지 않는다. CONFLICT는 낮추지 않고 그대로 올려 두 기록을 보존한 채 사람 검토로
 * 넘긴다.
 */
@Component
public class TroubleshootingJudge implements SimilarityJudgeStrategy {

    private static final Logger log = LoggerFactory.getLogger(TroubleshootingJudge.class);

    /** S4 판정 시스템 프롬프트 리소스 경로(코드가 아니라 콘텐츠라 파일로 분리). */
    private static final String PROMPT_PATH = "prompts/troubleshooting-judgement.md";

    private final String systemPrompt;

    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TroubleshootingJudge(PromptLoader promptLoader) {
        this.systemPrompt = promptLoader.load(PROMPT_PATH);
    }

    @Override
    public MemoryType supports() {
        return MemoryType.TROUBLESHOOTING;
    }

    @Override
    public Judgement judge(
            Map<String, Object> proposed, Map<String, Object> existing, UserAiContext ctx) {
        if (existing == null || existing.isEmpty()) {
            // 유사 후보 자체가 없으면 LLM을 부를 필요가 없다 — ctx.requireChat()도 호출하지 않는다.
            return new Judgement(Verdict.NEW, null, "유사한 기존 기억 없음");
        }
        LlmClient llmClient = ctx.requireChat();
        String raw;
        try {
            raw = llmClient.complete(systemPrompt, toUserPrompt(proposed, existing));
        } catch (RuntimeException e) {
            log.warn("LLM 판정 호출 실패 → fallback: {}", e.getMessage());
            return fallback();
        }
        return parseJudgement(raw);
    }

    private Judgement parseJudgement(String raw) {
        String json = LlmJson.extractObject(raw);
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

    /** LLM 판정 응답(필요 필드만). */
    private record JudgeResult(String verdict, String rationale) {}
}
