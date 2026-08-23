package com.recall.memory.type.troubleshooting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.prompt.LlmJson;
import com.recall.common.prompt.PromptLoader;
import com.recall.common.type.MemoryType;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.ExtractionStrategy;
import com.recall.memory.type.MemoryCard;
import java.util.List;
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
    public Class<? extends MemoryCard> cardType() {
        return TroubleshootingCard.class;
    }

    /**
     * 병합된 카드의 {@code status} 를 <b>결말을 말한 마지막 조각</b>의 값으로 맞춘다.
     *
     * <p>위치 규칙("첫 조각"·"마지막 조각")은 둘 다 틀린다. 앞 조각은 아직 해결 전이라 첫 값을 쓰면 결말을 놓치고, 조각 추출이 status 를 항상
     * 채우므로(모르는 값 → UNRESOLVED) 마지막 값을 쓰면 <b>결말을 말하지 않은 꼬리 조각</b>(검증 로그·후속 잡담)의 기본값이 앞의 RESOLVED 를
     * 덮어쓴다. 두 오류는 방향만 반대고 피해는 같다: 해결된 대화가 미해결로 저장되거나, 미해결 대화가 해결로 저장된다.
     *
     * <p>그래서 위치가 아니라 <b>발화 여부</b>로 고른다: {@link #assertsOutcome}를 만족하는 마지막 조각의 status 를 쓴다. 아무 조각도
     * 결말을 말하지 않았으면 병합 결과를 그대로 둔다(지어내지 않는다).
     *
     * <p>바꿀 때는 {@code info} 로 남긴다 — 조각 하나가 카드의 결론을 뒤집는 판단은 조용히 지나갈 일이 아니다(조용한 실패 금지).
     */
    @Override
    public MemoryCard reconcileMerged(MemoryCard merged, List<MemoryCard> partials) {
        TroubleshootingCard card = (TroubleshootingCard) merged;
        String outcome = lastAssertedStatus(partials);
        if (outcome == null || outcome.equals(card.status())) {
            return card;
        }
        log.info("S3 병합 결말 보정: status {} → {} (결말을 말한 마지막 조각 기준)", card.status(), outcome);
        return withStatus(card, outcome);
    }

    /** 결말을 말한 마지막 조각의 status. 아무 조각도 말하지 않았으면 null. */
    private static String lastAssertedStatus(List<MemoryCard> partials) {
        String found = null;
        for (MemoryCard partial : partials) {
            if (partial instanceof TroubleshootingCard ts && assertsOutcome(ts)) {
                found = ts.status();
            }
        }
        return found;
    }

    /**
     * 이 조각이 대화의 <b>결말을 말했는가</b>.
     *
     * <p>셋 중 하나면 말한 것으로 본다: (1) status 가 기본값 {@code UNRESOLVED} 가 아니다(모델이 명시적으로 판정했다), (2) 해결책이
     * 담겼다, (3) <b>판정된 결과</b>({@link #judged})를 가진 시도가 담겼다 — 통했든 실패했든 내용으로 결말을 증거한다. 반대로 <b>status 가
     * 기본값이고 해결책도 판정된 시도도 없는</b> 조각은 결말에 대해 아무 말도 하지 않은 조각이므로, 그 기본값이 다른 조각의 판정을 덮지 않게 한다.
     */
    private static boolean assertsOutcome(TroubleshootingCard card) {
        if (!TroubleshootingCard.UNRESOLVED.equals(card.status())) {
            return true;
        }
        if (!card.finalSolution().isBlank()) {
            return true;
        }
        return card.attempts().stream().anyMatch(a -> judged(a.outcome()));
    }

    /**
     * 이 시도가 <b>판정된</b> 결과를 담았는가 — {@code worked}·{@code failed}·{@code partial}.
     *
     * <p><b>왜 {@code worked} 만으로는 부족했나</b> — 전에는 통한 시도만 결말 발화로 봤다. 그러면 <b>후퇴는 앞의 성공을 뒤집지 못한다</b>: 앞
     * 조각이 {@code RESOLVED} + 해결책을 담고, 뒤 조각이 "그걸로 배포했는데 다시 터졌다"({@code status} 는 추출 기본값 {@code
     * UNRESOLVED}, {@code failed} 시도만 있고 해결책 없음)여도 뒤 조각이 발화로 안 잡혀 <b>앞의 {@code RESOLVED} 가 그대로
     * 저장됐다</b> — 고쳤다가 다시 깨진 대화가 해결됨으로 남는다.
     *
     * <p>{@code unknown} 은 제외한다: 모델이 결과를 판정하지 못했다는 표시이므로(Decision 13) 결말에 대해 아무 말도 하지 않은 것이다.
     *
     * <p>이 확장은 <b>Decision 13의 비대칭</b>을 따른다 — "해결됐다고 잘못 단정하는 쪽이 반대보다 위험하다". 판정된 시도를 담은 뒤 조각을 발화로
     * 인정하면 판단이 미해결 쪽으로 기울고, 그게 안전한 방향이다.
     */
    private static boolean judged(String outcome) {
        return TroubleshootingCard.Attempt.WORKED.equals(outcome)
                || TroubleshootingCard.Attempt.FAILED.equals(outcome)
                || TroubleshootingCard.Attempt.PARTIAL.equals(outcome);
    }

    private static TroubleshootingCard withStatus(TroubleshootingCard card, String status) {
        return new TroubleshootingCard(
                card.title(),
                card.summary(),
                card.keywords(),
                card.symptom(),
                card.errorMessage(),
                card.errorSignature(),
                card.environment(),
                card.attempts(),
                card.rootCause(),
                card.finalSolution(),
                status);
    }

    @Override
    public MemoryCard extract(String maskedText, UserAiContext ctx) {
        // 카드를 Map 으로 눌러 반환하지 않는다 — 경계가 Map 이면 타입 안전성이 이 패키지 밖에서 끊기고,
        // 읽는 쪽(memory·review·query 모듈)이 필드 이름을 문자열로 다시 적게 된다.
        return extractCard(maskedText, ctx.requireChat());
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

    /**
     * 원문을 유실하지 않는 최소 카드 — 제목은 원문 앞부분, <b>증상에 원문 전문</b>을 남긴다. 원인·해결은 <b>비워 둔다</b>(모르는 것을 채우지 않는다).
     *
     * <p>{@code summary}에는 원문 전문을 넣지 않는다. 승인 시 BM25 색인({@code ReviewService.keywordText})이 읽는 필드가
     * {@code title + summary + keywords}뿐이어서, 요약에 대화 전문이 들어가면 그 카드의 {@code search_tsv}가 대화 전체 어휘를
     * 갖는다 — LLM 장애 구간에 만들어진 fallback 카드들이(원문이 보존돼 있어 승인할 만해 보인다) 이후 이 유형의 거의 모든 질문에서 BM25 상위를
     * 차지하는데, 정작 root_cause·final_solution 은 빈 카드다. 트러블슈팅은 BM25 가 주 채널(2.0)이라 영향이 증폭된다. 원문은 색인 대상이
     * 아닌 {@code symptom}에 그대로 남으므로 <b>유실이 아니다</b>.
     */
    private TroubleshootingCard fallback(String maskedText) {
        return new TroubleshootingCard(
                deriveTitle(maskedText),
                deriveTitle(maskedText),
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
