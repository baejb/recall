package com.recall.store.service;

import com.recall.common.prompt.PromptLoader;
import com.recall.common.type.MemoryType;
import com.recall.common.type.MemoryTypeMatch;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.ExtractionStrategy;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 저장 경로 유형 라우팅 — 마스킹된 원문이 어느 memory 유형인지 정한다(S2 추출 앞단). PRD의 분류(C) 중 domain 축에 해당하는 🔵 확률적 단계다.
 *
 * <p><b>지원되는 유형으로만 분류한다.</b> 후보는 등록된 {@link ExtractionStrategy}의 유형이다 — 추출 전략이 없는 유형으로 라우팅하면 파이프라인이
 * 바로 터지기 때문이고, 덕분에 새 유형 전략이 자가 등록되는 순간 분류가 자동으로 켜진다. 유형이 하나뿐이면 분류가 무의미하므로 <b>LLM을 부르지 않는다</b>(비용·지연
 * 절약).
 *
 * <p>격하 규칙(조용한 실패 금지 — 모두 로그로 드러낸다): 호출 실패·모르는 출력·미지원 유형 출력은 기본 유형으로 격하한다. 유형을 잘못 골라도 원문은 capture에
 * 그대로 남아 있어 되돌릴 수 있고, 잘못 고른 카드는 검토 단계에서 사람이 반려할 수 있다(승인 게이트).
 *
 * <p>유형 이름 매칭은 {@code MemoryType.name()}으로 한다 — 유형별 키워드 표를 코드에 두지 않으므로 유형이 늘어도 이 클래스는 고치지 않는다(OCP).
 * 유형의 <b>설명</b>은 코드가 아니라 프롬프트 리소스({@code prompts/type-classification.md})에 있다(프롬프트=콘텐츠 규약). 매칭 규칙
 * 자체는 {@link MemoryTypeMatch}에 있다 — 조회 경로의 분류(C)와 같은 판단이라 규칙을 공유한다.
 */
@Component
public class TypeClassifier {

    private static final Logger log = LoggerFactory.getLogger(TypeClassifier.class);

    /** 유형 라우팅 시스템 프롬프트 리소스 경로. */
    private static final String PROMPT_PATH = "prompts/type-classification.md";

    /**
     * 라우팅 판단에 쓸 원문 앞부분 최대 길이. 유형은 보통 도입부에서 드러나므로 전문을 넣지 않는다(토큰 비용·지연 통제).
     *
     * <p>이 절단은 <b>라우팅 입력에만</b> 적용된다 — 추출(S2/S3)은 원문 전체를 겹침 청킹으로 커버하므로 내용이 유실되지 않는다.
     */
    static final int ROUTING_MAX_CHARS = 4_000;

    private final String systemPrompt;
    private final Set<MemoryType> supported;

    public TypeClassifier(
            PromptLoader promptLoader, List<ExtractionStrategy> extractionStrategies) {
        this.systemPrompt = promptLoader.load(PROMPT_PATH);
        // 레지스트리로 감싸면 유형 중복 등록도 여기서 걸린다(부팅 실패로 드러남).
        this.supported = new StrategyRegistry<>(extractionStrategies).registered();
    }

    /**
     * 마스킹된 원문의 memory 유형. LLM 호출은 {@code ctx.requireChat()}으로 얻은 클라이언트만 쓴다 — 전역 싱글턴이 아니라 capture
     * 소유자에 바인딩된 클라이언트다(사용자별 provider/키 교차유출 방지). 저장 파이프라인이 이 단계 전에 {@code ctx.chatReady()}를 확인한다.
     *
     * @param maskedText M0 마스킹을 이미 거친 원문(마스킹 우선 — 원문은 여기 도달하지 않는다)
     */
    public MemoryType classify(String maskedText, UserAiContext ctx) {
        MemoryType fallback = defaultType();
        if (supported.size() <= 1) {
            return fallback;
        }
        try {
            String output = ctx.requireChat().complete(systemPrompt, buildPrompt(maskedText));
            MemoryType matched = MemoryTypeMatch.exactlyOne(output, supported);
            if (matched == null) {
                // 유형 이름이 없거나 둘 이상 함께 등장(부정문 등) → 판단 근거가 없으니 격하하고 드러낸다.
                log.warn("유형 라우팅 출력을 해석할 수 없음(무매치·모호) → 기본 {} (출력: {})", fallback, output);
                return fallback;
            }
            return matched;
        } catch (RuntimeException e) {
            log.warn("유형 라우팅 실패 → 기본 {}: {}", fallback, e.getMessage());
            return fallback;
        }
    }

    /** 지원 유형 중 KNOWLEDGE가 있으면 그것을, 없으면 등록된 첫 유형(조회 경로 분류와 같은 규칙). */
    private MemoryType defaultType() {
        if (supported.isEmpty() || supported.contains(MemoryType.KNOWLEDGE)) {
            return MemoryType.KNOWLEDGE;
        }
        return supported.iterator().next();
    }

    /** 후보 유형 목록 + 마스킹된 원문(앞부분)으로 사용자 프롬프트를 만든다. */
    private String buildPrompt(String maskedText) {
        String text = maskedText == null ? "" : maskedText.strip();
        if (text.length() > ROUTING_MAX_CHARS) {
            // 격하·절단은 모두 warn 으로 통일한다 — debug 는 운영 기본 레벨에서 사라져 "잘린 입력은 항상 상태로
            // 노출"(불변 원칙 6)을 만족하지 못했다. 이 절단은 라우팅 입력에만 적용되고 추출은 전문을 처리하므로
            // 내용 유실은 아니지만, 유형 오분류의 원인을 사후에 추적하려면 보여야 한다.
            log.warn(
                    "유형 라우팅은 원문 앞 {}자만 사용한다({}자 입력, 추출은 전문을 처리)", ROUTING_MAX_CHARS, text.length());
            text = text.substring(0, ROUTING_MAX_CHARS);
        }
        String candidates =
                supported.stream().map(MemoryType::name).reduce((a, b) -> a + ", " + b).orElse("");
        return "후보 유형: " + candidates + "\n\n원문:\n" + text;
    }
}
