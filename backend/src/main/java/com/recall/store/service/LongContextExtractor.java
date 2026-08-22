package com.recall.store.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.prompt.LlmJson;
import com.recall.common.prompt.PromptLoader;
import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.ExtractionStrategy;
import com.recall.memory.type.MemoryCard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 *
 * <p><b>병합 결과도 카드다</b> — 예전에는 이 단계만 {@code Map<String,Object>}를 그대로 돌려줘서, 병합된 카드가 유형 스키마 ({@code
 * TroubleshootingCard} 등)의 생성자를 <b>한 번도 통과하지 않고</b> DB·API까지 갔다. 그래서 status·outcome 정규화와
 * error_signature→keywords 병합이 긴 원문에서만 조용히 빠졌다. 지금은 LLM 병합이든 결정론 병합이든 {@link CardCodec}을 거쳐 카드로
 * 돌아오므로 정규화가 구조적으로 보장된다.
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
    private final CardCodec cardCodec;
    private final String mergeSystemPrompt;
    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LongContextExtractor(
            List<ExtractionStrategy> extractionStrategies,
            CardCodec cardCodec,
            PromptLoader promptLoader) {
        this.extractions = new StrategyRegistry<>(extractionStrategies);
        this.cardCodec = cardCodec;
        this.mergeSystemPrompt = promptLoader.load(MERGE_PROMPT_PATH);
    }

    /**
     * 유형별 구조화 추출(S2/S3). 짧으면 단일 패스, 길면 청킹 후 Map-Reduce. 반환은 S2와 동일한 {@link MemoryCard}라 이후
     * 판정(S4)·검토 흐름은 그대로다. Reduce(병합) LLM 호출은 {@code ctx.requireChat()}으로 얻는다 — 주입된 전역 싱글턴이 아니라
     * capture 소유자에 바인딩된 클라이언트만 쓴다(사용자별 provider/키 교차유출 방지).
     */
    public MemoryCard extract(MemoryType type, String maskedText, UserAiContext ctx) {
        ExtractionStrategy strategy = extractions.get(type);
        if (maskedText == null || maskedText.length() <= SINGLE_PASS_MAX_CHARS) {
            return strategy.extract(maskedText, ctx); // 단일 패스(S2)
        }
        List<String> chunks = chunk(maskedText, CHUNK_CHARS, OVERLAP_CHARS);
        log.info("S3 긴맥락 추출: {}자 → {}조각 Map-Reduce", maskedText.length(), chunks.size());
        List<MemoryCard> partials =
                chunks.stream()
                        .map(chunkText -> strategy.extract(chunkText, ctx))
                        .toList(); // Map — 조각마다 S2
        return reduce(type, partials, ctx); // Reduce
    }

    /** 부분 카드들을 하나로 병합(Reduce). LLM 병합을 우선하고, 미가용/실패/파싱불가면 결정론 병합으로 격하한다. */
    private MemoryCard reduce(MemoryType type, List<MemoryCard> partials, UserAiContext ctx) {
        if (partials.size() == 1) {
            return partials.get(0);
        }
        List<Map<String, Object>> partialFields = partials.stream().map(cardCodec::toMap).toList();
        ExtractionStrategy strategy = extractions.get(type);
        LlmClient llmClient = ctx.requireChat();
        if (!llmClient.available()) {
            return mergeToCard(type, partialFields, strategy, partials); // stub → 결정론 병합
        }
        try {
            String raw = llmClient.complete(mergeSystemPrompt, toJson(partials));
            Map<String, Object> merged = parseCard(raw);
            if (merged == null || merged.isEmpty()) {
                log.warn("S3 병합 응답 파싱 실패 → 결정론 병합");
                return mergeToCard(type, partialFields, strategy, partials);
            }
            // 리스트는 LLM 이 아니라 결정론 union 이 소유한다 — 아래 withUnionLists 참조.
            merged = withUnionLists(merged, partialFields);
            // 파싱 성공 ≠ 쓸 수 있는 카드. 두 가지가 여기까지 통과한다: (1) 모양이 다른 응답(카드를 한 겹 감싼
            // {"merged": {...}}, 산문 속의 무관한 JSON) — 코덱이 변환하면 모든 필드가 정규화 기본값인 "빈 카드"가
            // 되고, (2) 모양은 맞지만 부분 카드가 채웠던 스칼라를 떨어뜨린 응답. 둘 다 결정론 병합으로 격하한다.
            if (!preservesPartialContent(merged, partialFields)) {
                log.warn("S3 병합 응답을 카드로 쓸 수 없음(모양·내용 검사 실패) → 결정론 병합");
                return mergeToCard(type, partialFields, strategy, partials);
            }
            // 코덱을 통과시켜 유형 스키마의 정규화를 강제한다 — LLM 이 status 를 "해결됨" 처럼 열거값 밖의
            // 표기로 내도 여기서 카드 규약(UNRESOLVED 등)으로 정규화된다.
            // 그 다음 유형 후처리 — LLM 도 결말을 잘못 고를 수 있고(프롬프트를 준수해도), 그 판단은
            // 유형만 할 수 있다. 그래서 LLM 경로와 결정론 경로가 <b>같은</b> 훅을 거친다.
            return strategy.reconcileMerged(cardCodec.read(type, merged), partials);
        } catch (RuntimeException e) {
            log.warn("S3 병합 실패 → 결정론 병합: {}", e.getMessage());
            return mergeToCard(type, partialFields, strategy, partials);
        }
    }

    /**
     * 병합 결과의 <b>모든 리스트 필드를 결정론 union 으로 교체</b>한다(LLM 이 낸 리스트는 버린다).
     *
     * <p><b>왜 검사 대신 교체인가</b> — 리스트 유실을 "사후 검사"로 막으려 했지만 개수만 세는 검사는 <b>교차 유실을 통과시킨다</b>: 조각이 {@code
     * [a,b]}·{@code [c,d]} 이고 병합이 {@code [a,c]} 면 개수(2)가 가장 긴 조각(2)과 같아 통과하면서 b·d 를 잃는다. 개수를 조각 전체의
     * union 수로 올려도 안 된다 — 겹침 청킹은 같은 시도를 <b>표현만 다르게</b> 두 번 추출하므로, 그 정상적인 dedup 까지 유실로 오판해 LLM 병합
     * 경로가 사실상 죽는다. 소속(membership)으로 증명하려 해도 LLM 이 문장을 다시 쓰면 동일성 비교가 성립하지 않는다.
     *
     * <p>즉 <b>"다시 쓴 것"과 "버린 것"을 결정론으로 구별할 방법이 없다</b>. 그래서 검사를 정교하게 만드는 대신 <b>LLM 에게서 리스트 병합 권한을
     * 뺐다</b>: 리스트 합치기는 union 이라는 모호함 없는 연산이므로 불변 원칙 4("결정론 단계에 LLM 금지")가 그대로 적용되는 자리다. LLM 은 요약·서술
     * 같은 스칼라 필드만 다듬는다.
     *
     * <p>대가: 표현이 다른 중복 항목은 이제 각각 남는다(전에는 LLM 이 합칠 수 있었다). 그 대가를 받아들이는 근거는 이 클래스의 규약과 같다 — <b>요약 품질
     * &lt; 사실 보존</b>. 게다가 격하 경로가 이미 같은 대가를 치르고 있었으므로, 스칼라 품질만 살리는 지금이 전보다 낫다.
     *
     * <p>부분 카드에 있던 리스트 키는 LLM 응답에 <b>없어도</b> 채워 넣는다(리스트를 통째로 떨어뜨린 응답도 여기서 복구된다).
     */
    private static Map<String, Object> withUnionLists(
            Map<String, Object> merged, List<Map<String, Object>> partials) {
        Map<String, Object> union = mergeDeterministic(partials);
        Map<String, Object> result = new LinkedHashMap<>(merged);
        union.forEach(
                (key, value) -> {
                    if (value instanceof List<?>) {
                        result.put(key, value);
                    }
                });
        return result;
    }

    /** 결정론 병합 결과를 카드로 되돌리고 유형 후처리를 거친다 — 두 경로가 같은 규약을 갖도록. */
    private MemoryCard mergeToCard(
            MemoryType type,
            List<Map<String, Object>> partialFields,
            ExtractionStrategy strategy,
            List<MemoryCard> partials) {
        return strategy.reconcileMerged(
                cardCodec.read(type, mergeDeterministic(partialFields)), partials);
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

    /**
     * 결정론 병합(격하): 리스트 필드는 중복 제거 union(사실 유실 방지), 스칼라 필드는 첫 non-blank 값. 스키마 무관하게 동작한다 — 그래서 카드가 아니라
     * 필드 맵을 받는다(유형별 필드 이름을 공유 코드가 알지 않게).
     *
     * <p><b>첫 non-blank 규칙이 어디서 틀리는가</b> — 스칼라가 title·summary·document 처럼 서술 필드면 어느 조각을 골라도 크게 틀리지
     * 않지만, <b>시간 순서에 의미가 있는 상태값</b>이면 앞 조각이 이긴다. 트러블슈팅 카드가 그렇다: 앞 조각은 아직 해결 전이라 {@code
     * status=UNRESOLVED}(이 값은 카드 생성자가 항상 채우므로 blank 로 건너뛰지도 않는다), 뒷 조각이 {@code RESOLVED} + 원인·해결을
     * 낸다 → 원인·해결은 뒷 값이 들어오는데 <b>상태만 미해결로 남는</b> 모순된 카드가 된다.
     *
     * <p><b>그렇다고 위치 규칙으로 결말을 맞히려 하지 않는다</b> — 한때 유형이 지목한 키에 "마지막 non-blank"를 적용했는데(이전 {@code
     * lastWinsFields}), 조각 추출이 그 필드를 <b>항상</b> 채우므로 그건 사실상 "무조건 마지막 조각"이 됐고, 결말을 말하지 않은 꼬리 조각(검증
     * 로그·후속 잡담)의 기본값이 앞의 판정을 덮었다 — 같은 모순의 거울상. 결말이 어느 조각에 있는지는 유형별 필드를 봐야 알 수 있으므로 그 판단은 병합
     * <b>후</b>에 유형이 한다({@link ExtractionStrategy#reconcileMerged}). 이 메서드는 스키마를 전혀 모르는 상태로 남는다.
     *
     * <p>조각마다 값이 다른 스칼라는 <b>조용히 고르지 않고 드러낸다</b>(불변 원칙 6): 그 키들을 모아 warn 으로 남겨, 검토 게이트에서 사람이 그 카드를
     * 의심할 근거를 만든다.
     */
    static Map<String, Object> mergeDeterministic(List<Map<String, Object>> partials) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> conflicting = new LinkedHashSet<>();
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
                } else if (value != null) {
                    boolean blank = value instanceof String s && s.isBlank();
                    if (blank) {
                        continue;
                    }
                    if (!result.containsKey(key)) {
                        result.put(key, value); // 첫 non-blank 스칼라
                    } else if (!value.equals(result.get(key))) {
                        conflicting.add(key); // 조각들이 서로 다른 값을 냈다
                    }
                }
            }
        }
        if (!conflicting.isEmpty()) {
            log.warn(
                    "S3 결정론 병합: 조각마다 값이 다른 스칼라 필드 {} — 첫 조각 값을 유지했다."
                            + " 결말을 담은 필드라면 유형의 reconcileMerged() 가 병합 후에 바로잡는다.",
                    conflicting);
        }
        return result;
    }

    private String toJson(List<MemoryCard> partials) {
        try {
            return objectMapper.writeValueAsString(partials);
            // FQN 대신 상단 import 를 쓴다(backend/CLAUDE.md: 본문에서 FQN 금지 — 가독성·spotless 정합).
        } catch (RuntimeException | JsonProcessingException e) {
            throw new IllegalStateException("부분 카드 직렬화 실패", e);
        }
    }

    /**
     * LLM 병합 응답에서 JSON 객체 구간을 잘라 Map으로 파싱한다. 실패하면 null(격하 판단은 호출부).
     *
     * <p>JSON 구간을 잘라내는 로직은 {@link LlmJson#extractObject}에 이미 있다 — 여기에 같은 코드가 손으로 한 번 더 적혀 있었다(S2
     * 추출·S4 판정 4곳을 {@code LlmJson}으로 합칠 때 이 5번째 사본만 빠졌다). 잘라내기 규칙이 갈리면 같은 응답을 단계마다 다르게 읽게 되므로 공유
     * 유틸을 쓴다.
     *
     * <p>{@code catch (Exception)}으로 원인을 버리지 않고 메시지를 남긴다 — 호출부는 "파싱 실패"만 알 수 있어 문법 오류인지 타입 불일치인지
     * 구분할 단서가 어디에도 없었다(조용한 실패 금지).
     */
    private Map<String, Object> parseCard(String raw) {
        String json = LlmJson.extractObject(raw);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("S3 병합 응답 JSON 파싱 실패: {}", e.getOriginalMessage());
            return null;
        }
    }

    /**
     * 병합 결과를 카드로 받아들일 수 있는지 두 단계로 본다 — ① <b>모양</b>: 부분 카드의 키를 하나라도 공유하는가, ② <b>내용</b>: 부분 카드가 채웠던
     * 필드가 병합 결과에도 남아 있는가. 유형별 필드 이름을 몰라도 부분 카드와 대조하면 되므로 스키마 무관이다.
     *
     * <p><b>내용 검사를 왜 더했나</b> — 처음에는 모양만 봤고(키 하나라도 공유하면 통과) "값이 빈 필드를 떨어뜨리는 것까지 실패로 볼 필요는 없다"고 적었다.
     * 그런데 그 느슨함이 이 클래스가 선언한 불변식을 정확히 깨뜨렸다: 부분 카드에 {@code facts}·{@code attempts}가 채워져 있는데 병합 응답이
     * {@code {"title":"병합됨"}} 하나뿐이면, {@code title}을 공유하므로 모양 검사를 통과하고 → 코덱이 빠진 리스트를 <b>빈 리스트로
     * 정규화</b>하고 → 결정론 병합(union)으로 격하되지도 않아 <b>추출된 사실이 조용히 사라진다</b>. "Reduce 실패 시 결정론 병합으로 격하해 사실을
     * 유실하지 않는다"는 이 클래스의 규약은 LLM 이 <b>실패할 때</b>만 지켜지고 있었고, 이렇게 "성공하면서 내용을 떨어뜨리는" 경로에는 방어가 없었다.
     *
     * <p>그래서 부분 카드에서 내용이 있던 키가 병합 결과에서 비면 병합 실패로 본다. 격하 방향이 안전하다: 결정론 병합은 리스트를 union 하므로 사실을 보존하고,
     * 잃는 것은 LLM 이 다듬은 요약 문장뿐이다(요약 품질 &lt; 사실 보존). 프롬프트도 이미 "입력 카드와 같은 키 집합을 유지한다"·"없는 사실을 지어내지
     * 않는다"를 요구하므로, 여기서 거절되는 응답은 프롬프트 위반이다.
     *
     * <p>없던 키가 새로 생긴 것은 유실이 아니므로 거절하지 않고 warn 으로만 드러낸다(조용한 실패 금지).
     */
    private boolean preservesPartialContent(
            Map<String, Object> merged, List<Map<String, Object>> partials) {
        Set<String> allKeys = new LinkedHashSet<>();
        Set<String> keysWithContent = new LinkedHashSet<>();
        for (Map<String, Object> partial : partials) {
            partial.forEach(
                    (key, value) -> {
                        allKeys.add(key);
                        if (hasContent(value)) {
                            keysWithContent.add(key);
                        }
                    });
        }

        // ① 모양 — 공유 키가 하나도 없으면 카드가 아니다(한 겹 감싼 객체·무관한 JSON).
        Set<String> shared = new LinkedHashSet<>(merged.keySet());
        shared.retainAll(allKeys);
        if (shared.isEmpty()) {
            return false;
        }

        // ② 내용 — 부분 카드가 채웠던 필드가 병합 결과에서 비었다면 유실이다.
        Set<String> lost =
                keysWithContent.stream()
                        .filter(key -> !hasContent(merged.get(key)))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!lost.isEmpty()) {
            log.warn("S3 병합 응답이 부분 카드의 내용을 떨어뜨렸다 — 비워진 필드 {}", lost);
            return false;
        }

        // 리스트 항목 유실을 여기서 검사하지 않는 이유: 검사 대신 소유권을 옮겼다(withUnionLists).
        // 개수 검사는 교차 유실([a,b]+[c,d] → [a,c])을 통과시키고, 소속 검사는 LLM 이 문장을 다시 쓰면
        // 성립하지 않는다. 리스트는 이 검사에 오기 전에 이미 결정론 union 으로 교체돼 있다.

        Set<String> added = new LinkedHashSet<>(merged.keySet());
        added.removeAll(allKeys);
        if (!added.isEmpty()) {
            log.warn("S3 병합 결과에 부분 카드에 없던 키가 생겼다(유실은 아니므로 통과) — {}", added);
        }
        return true;
    }

    /**
     * "이 값에 내용이 있는가" — 빈 문자열·빈 리스트·빈 맵·null 은 내용 없음.
     *
     * <p>카드 스키마가 null 을 빈 값으로 정규화하므로(널 전파 차단) 부분 카드에는 값 없는 필드도 키로 존재한다. 그래서 키 존재만으로는 내용 유무를 알 수 없고
     * 값을 봐야 한다. 유형별 필드 이름은 여전히 알지 않는다.
     */
    private static boolean hasContent(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }
}
