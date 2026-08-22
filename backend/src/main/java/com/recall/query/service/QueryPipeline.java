package com.recall.query.service;

import com.recall.common.type.MemoryType;
import com.recall.common.type.MemoryTypeMatch;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.service.entity.Memory;
import com.recall.memory.type.AnswerContribution;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.MemoryCard;
import com.recall.query.controller.dto.AnswerFragment;
import com.recall.search.service.HybridSearchService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 파이프라인: 질문 → 분류(C) → 하이브리드 검색(R·W) → 리랭크(RR) → 답변(A). 답변은 저장된 근거(memory)에 매이고, 근거가 없으면 지어내지 않고
 * 빈 결과(상위가 "기록 없음")를 낸다.
 *
 * <p>LLM이 필요한 단계(C·RR·A)는 호출부가 넘긴 {@link UserAiContext#requireChat()}로 그때그때 클라이언트를 얻는다 — 주입된 전역
 * {@code LlmClient} 싱글턴을 쓰지 않는다(사용자별 provider/키 교차유출 방지). chat 미설정(요청 자체가 차단돼야 하는 경우)은 이미 조회 입구
 * ({@code QueryController})에서 걸러지므로, 여기서의 {@code requireChat()}은 방어적 가드다 — 정상 흐름에서는 던지지 않는다.
 *
 * <p>분류(C)는 LLM이 질문 유형을 판정하되 <b>등록된 유형이 1개뿐이면 LLM 없이 그 유형</b>으로 격하한다(TS 전략이 자가등록되면 자동 활성화).
 * 검색(R·W)은 vector+BM25 융합(결정론) — 임베딩 채널은 {@code ctx.embeddingReady()}일 때만 쓴다(미설정이면 BM25만으로 격하, 요청을
 * 막지 않는다). 리랭크(RR)는 W 상위 후보를 LLM이 질문 관련도로 재정렬한다(호출 실패 시 W 순서 유지 — 격하). 답변(A)은 근거만으로 LLM이 재구성해 토큰
 * 단위로 스트리밍한다(호출 실패 시 요약 격하). 설정 미완료(차단)와 설정 완료 후 외부 API 호출 실패(격하)는 서로 다른 상황이다 — 하나로 섞지 않는다.
 */
@Component
public class QueryPipeline {

    private static final Logger log = LoggerFactory.getLogger(QueryPipeline.class);

    /** RR: LLM에 넣어 재정렬할 W 상위 후보 수(프롬프트 크기 통제). */
    static final int RR_INPUT_MAX = 10;

    /** RR 후 A에 넘길 최대 근거 수(토큰 통제·근거 품질). */
    static final int RR_OUTPUT_MAX = 6;

    /** C(분류) 시스템 프롬프트 — 질문 유형을 한 단어로. */
    static final String CLASSIFY_SYSTEM =
            """
            다음 질문이 '트러블슈팅'(에러·장애·버그·실패의 원인/해결 회상)인지, '지식'(개념·정의·방법·결정 등 그 외)인지 분류한다.
            TROUBLESHOOTING 또는 KNOWLEDGE 중 한 단어만 출력한다.
            """;

    /** RR(리랭크) 시스템 프롬프트 — 관련도 순 번호 배열만 출력. */
    static final String RERANK_SYSTEM =
            """
            질문에 대한 각 근거의 실제 관련도를 판단해, 관련도가 높은 순서로 근거 번호를 JSON 정수 배열로만 출력한다. 예: [3,1,2].
            표면적 키워드 겹침이 아니라 질문에 답하는 데 도움이 되는 정도로 판단한다. 배열 외의 다른 텍스트는 출력하지 않는다.
            """;

    /** A(답변) 그라운딩 시스템 프롬프트 — 근거에 매인 답만 허용(근거 없는 생성 금지). */
    static final String ANSWER_SYSTEM =
            """
            너는 Recall의 답변 작성기다. 아래 '근거'에 담긴 내용만으로 사용자의 질문에 답한다.
            - 근거에 없는 사실·수치·결론은 절대 지어내지 않는다.
            - 근거가 질문에 답하기 부족하면 "기록 없음"이라고만 답한다.
            - 근거를 그대로 나열하지 말고, 질문에 맞게 간결한 한국어로 재구성한다.
            """;

    private static final Pattern INT_TOKEN = Pattern.compile("\\d{1,3}");

    private final HybridSearchService searchService;
    private final StrategyRegistry<AnswerContribution> answers;

    /** 카드 되읽기는 이 코덱만 한다(모듈마다 ObjectMapper 를 두면 되읽기가 유형 스키마를 건너뛴다). */
    private final CardCodec cardCodec;

    public QueryPipeline(
            HybridSearchService searchService,
            CardCodec cardCodec,
            List<AnswerContribution> answerContributions) {
        this.searchService = searchService;
        this.cardCodec = cardCodec;
        this.answers = new StrategyRegistry<>(answerContributions);
    }

    /**
     * C — 질문 유형 분류(🔵 확률적). <b>지원되는 유형으로만</b> 분류한다: 전략이 등록된 유형이 1개뿐이면(현재 KNOWLEDGE만) 분류가 무의미하므로
     * LLM을 부르지 않고 그 유형을 반환한다 — 새 유형(예: TROUBLESHOOTING) 전략이 자가등록되면 자동으로 분류가 켜진다. 유형이 2개 이상이면 {@code
     * ctx.requireChat()}로 클라이언트를 얻는다(정상 흐름에선 조회 입구가 이미 chatReady를 보장). 호출 실패/미지원 유형 출력은 기본 유형으로
     * 격하한다(조용한 실패 금지). 유형별로 검색 표현·플랜 가중치·답변 전략이 갈린다.
     */
    public MemoryType classify(String question, UserAiContext ctx) {
        Set<MemoryType> supported = answers.registered();
        MemoryType fallback =
                supported.isEmpty() || supported.contains(MemoryType.KNOWLEDGE)
                        ? MemoryType.KNOWLEDGE
                        : supported.iterator().next();
        if (supported.size() <= 1) {
            return fallback; // 유형이 하나뿐 → 분류 불필요, chat 미설정이어도 도달 가능해야 한다.
        }
        LlmClient llmClient = ctx.requireChat();
        try {
            MemoryType type = parseType(llmClient.complete(CLASSIFY_SYSTEM, question), supported);
            if (type == null) {
                log.warn("C 분류 출력을 해석할 수 없음(무매치·모호) → 기본 {}", fallback);
                return fallback;
            }
            return type;
        } catch (RuntimeException e) {
            log.warn("C 분류 실패 → 기본 {}: {}", fallback, e.getMessage());
            return fallback;
        }
    }

    /**
     * 하이브리드 검색(R·W) → 근거 후보(주어진 유형으로). 소유자·임베딩 채널 가용성은 {@code ctx}로 관통한다(요청 입력 userId 신뢰 금지). 트랜잭션은
     * 검색 쿼리에만 걸고(느린 LLM 호출은 트랜잭션 밖), 이후 리랭크·답변은 로드된 memory(structured 컬럼)만 사용해 커넥션을 오래 점유하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Memory> retrieve(String question, MemoryType type, UserAiContext ctx) {
        return readable(searchService.search(question, type, ctx));
    }

    /**
     * 카드를 읽을 수 없는 후보를 <b>여기 한 곳에서</b> 걸러낸다 — 이후 단계(리랭크·답변·격하)는 전부 읽을 수 있는 근거만 본다.
     *
     * <p>필터를 하류에 흩뿌리지 않는 이유가 하나 더 있다: 리랭크는 프롬프트에 번호를 매기고 LLM이 돌려준 번호로 {@code pool} 을 인덱싱하므로, 프롬프트를
     * 만들 때만 후보를 빼면 <b>번호와 후보가 어긋나 엉뚱한 근거가 상위로 올라간다</b>. 입구에서 한 번 걸러 목록 하나만 흐르게 한다.
     */
    private List<Memory> readable(List<Memory> candidates) {
        List<Memory> usable = candidates.stream().filter(m -> card(m) != null).toList();
        if (usable.size() != candidates.size()) {
            log.warn(
                    "근거 후보 {}건 중 {}건은 카드를 읽을 수 없어 제외했다",
                    candidates.size(),
                    candidates.size() - usable.size());
        }
        return usable;
    }

    /**
     * LLM 분류 출력에서 지원 유형을 뽑는다. 정하지 못하면(무매치·모호) {@code null} — 격하는 호출부가 로그와 함께 한다.
     *
     * <p>이전에는 {@code "TROUBLE"}·{@code "트러블"}·{@code "트슈"} 한국어 키워드 표를 두고 그 외는 전부 KNOWLEDGE 로 떨어뜨렸다.
     * 문제가 둘이었다: (1) 세 번째 유형이 SPI를 갖춰 자가등록돼도 이 함수가 그 유형을 <b>반환할 수 없어</b> {@code
     * supported.contains(...)} 검사를 통과한 KNOWLEDGE 로 <b>격하 로그도 없이</b> 검색됐다 — "전략을 등록하면 분류가 자동으로 켜진다"는
     * 계약이 조회 경로에서만 거짓이었다. (2) 저장 경로({@code TypeClassifier})는 {@code MemoryType.name()} 매칭인데 여기만 규칙이
     * 달라, 같은 문장이 저장 때와 조회 때 다른 유형으로 분류될 수 있었다. 규칙을 {@link MemoryTypeMatch}로 합쳐 두 경로가 같은 답을 내게 한다.
     *
     * <p>키워드 표를 지운 대가로, 모델이 영어 유형 이름 대신 한국어로 답하면 이제 매칭되지 않는다. 그건 격하 대상이며 <b>warn 으로 드러난다</b>(전에는
     * 조용히 KNOWLEDGE 였다). {@link #CLASSIFY_SYSTEM}이 이름 한 단어만 출력하라고 지시하고 있으므로 정상 흐름에서는 발생하지 않는다.
     *
     * <p>남은 결합: {@code CLASSIFY_SYSTEM}은 여전히 두 유형 이름·설명을 산문으로 갖고 있어, 새 유형이 <b>후보로 제시되지는</b> 않는다. 그걸
     * 없애려면 저장 경로처럼 프롬프트 리소스 + 후보 목록 주입으로 옮겨야 하는데, 조회 경로는 "질문"을 분류하므로 저장용 {@code
     * type-classification.md}를 그대로 쓸 수 없다(새 프롬프트 리소스가 필요 — 이번 수정 범위 밖).
     */
    static MemoryType parseType(String out, Set<MemoryType> supported) {
        return MemoryTypeMatch.exactlyOne(out, supported);
    }

    /**
     * RR — W 상위 후보를 LLM이 질문 관련도로 재정렬해 상위 {@link #RR_OUTPUT_MAX}개를 낸다. 후보가 1개 이하면 재정렬은 무의미하므로 chat
     * 호출 없이 그대로 둔다. 그 외엔 {@code ctx.requireChat()}로 클라이언트를 얻는다(정상 흐름에선 조회 입구가 이미 chatReady를 보장). 호출
     * 실패·파싱 실패는 W 순서를 유지한다(격하 — 조용한 실패 금지). LLM이 누락한 후보는 뒤에 붙여 근거 유실을 막는다.
     */
    public List<Memory> rerank(String question, List<Memory> candidates, UserAiContext ctx) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<Memory> pool =
                candidates.size() > RR_INPUT_MAX ? candidates.subList(0, RR_INPUT_MAX) : candidates;
        List<MemoryCard> poolCards = cards(pool);
        if (poolCards.size() != pool.size()) {
            // 프롬프트 번호와 pool 인덱스가 어긋나면 LLM 이 지목한 번호가 엉뚱한 근거를 가리킨다 —
            // 잘못 재정렬하는 것보다 W 순서를 유지하는 쪽이 안전하다(retrieve 가 걸렀으니 정상 흐름에선 안 온다).
            log.warn("RR 리랭크 생략: 후보 {}건 중 카드로 읽은 것이 {}건", pool.size(), poolCards.size());
            return capped(candidates);
        }
        LlmClient llmClient = ctx.requireChat();
        try {
            String out = llmClient.complete(RERANK_SYSTEM, buildRerankPrompt(question, poolCards));
            List<Integer> order = parseOrder(out, pool.size());
            if (order.isEmpty()) {
                log.warn("RR 리랭크 파싱 실패 → W 순서 유지");
                return capped(candidates);
            }
            List<Memory> reranked = new ArrayList<>();
            for (int idx : order) {
                reranked.add(pool.get(idx - 1));
            }
            Set<Integer> used = new HashSet<>(order);
            for (int i = 1; i <= pool.size(); i++) {
                if (!used.contains(i)) {
                    reranked.add(pool.get(i - 1)); // LLM이 누락한 후보 뒤에 보존
                }
            }
            return capped(reranked);
        } catch (RuntimeException e) {
            log.warn("RR 리랭크 실패 → W 순서 유지: {}", e.getMessage());
            return capped(candidates);
        }
    }

    private static List<Memory> capped(List<Memory> memories) {
        return memories.size() > RR_OUTPUT_MAX ? memories.subList(0, RR_OUTPUT_MAX) : memories;
    }

    /**
     * 근거만으로 답을 합성해 토큰을 {@code onToken}으로 흘린다(A, 스트리밍). {@code ctx.requireChat()}로 클라이언트를 얻는다(정상
     * 흐름에선 조회 입구가 이미 chatReady를 보장). LLM 호출 실패는 예외로 드러낸다 — 호출부(AnswerStreamer)가 요약 격하를 결정한다.
     */
    public void composeStreaming(
            String question, List<Memory> candidates, Consumer<String> onToken, UserAiContext ctx) {
        ctx.requireChat()
                .completeStream(ANSWER_SYSTEM, buildEvidencePrompt(question, candidates), onToken);
    }

    /** 격하(호출 실패): 각 근거를 유형별 전략으로 렌더(요약)해 근거(memory id)와 함께 조각으로 낸다 — 나열이지만 근거에 매여 있다. */
    public List<AnswerFragment> fallbackFragments(List<Memory> candidates) {
        return candidates.stream()
                .map(m -> new AnswerFragment(render(m), m.getId()))
                .filter(fragment -> !fragment.text().isEmpty())
                .toList();
    }

    /**
     * 근거 하나를 유형 전략으로 렌더한다. 카드를 읽을 수 없으면 <b>빈 문자열</b>(그 근거는 빠진다).
     *
     * <p><b>왜 던지지 않는가</b> — 카드 하나가 깨졌다고 질의 전체를 죽이면 안 된다. 게다가 던지면 격하 자체가 무너졌다: 답변 합성이 이 read 에서 던지면
     * {@code AnswerStreamer} 가 "LLM 호출 실패"로 오분류해 격하 경로({@link #fallbackFragments})로 가는데, 그 경로가
     * <b>같은 read 로 또 던져</b> 스트림이 에러로 끝났다. 근거를 빼는 쪽이 근거 없는 답을 만드는 것보다 안전하고 (근거 없는 생성 금지), 남은 근거로는 정상
     * 응답할 수 있다.
     */
    private String render(Memory memory) {
        MemoryCard card = card(memory);
        if (card == null) {
            return "";
        }
        return answers.get(memory.getType()).render(card);
    }

    /** 저장된 structured JSON → 유형 카드. 읽을 수 없으면 로그를 남기고 {@code null}(조용한 실패 금지). */
    private MemoryCard card(Memory memory) {
        MemoryCard card = cardCodec.readOrNull(memory.getType(), memory.getStructured());
        if (card == null) {
            log.warn("근거 카드를 읽을 수 없다 memoryId={} type={}", memory.getId(), memory.getType());
        }
        return card;
    }

    /**
     * 후보들을 카드로 되읽는다(리랭크 프롬프트가 제목·요약을 접근자로 읽기 위해).
     *
     * <p>{@link #retrieve} 가 이미 읽을 수 없는 후보를 걸렀으므로 여기서는 크기가 유지된다. 그 전제가 깨지면 번호와 후보가 어긋나므로, 호출부가 크기를
     * 비교해 리랭크를 건너뛴다.
     */
    private List<MemoryCard> cards(List<Memory> memories) {
        return memories.stream().map(this::card).filter(Objects::nonNull).toList();
    }

    /** LLM 출력에서 관련도 순 근거 번호 배열을 뽑는다. 대괄호 안 정수만 취하고, 범위(1..poolSize) 밖·중복은 버린다. 배열이 없으면 빈 목록. */
    static List<Integer> parseOrder(String output, int poolSize) {
        if (output == null) {
            return List.of();
        }
        int lo = output.indexOf('[');
        int hi = output.lastIndexOf(']');
        if (lo < 0 || hi <= lo) {
            return List.of();
        }
        Matcher m = INT_TOKEN.matcher(output.substring(lo + 1, hi));
        List<Integer> order = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        while (m.find()) {
            int v = Integer.parseInt(m.group());
            if (v >= 1 && v <= poolSize && seen.add(v)) {
                order.add(v);
            }
        }
        return order;
    }

    /**
     * 질문 + 번호 매긴 후보(제목·요약)로 RR 사용자 프롬프트를 만든다.
     *
     * <p>제목·요약은 카드 접근자로 읽는다 — 전엔 {@code s.get("title")}·{@code s.get("summary")}로 이 모듈이 필드 이름을 문자열로
     * 다시 적었고, 카드 스키마가 바뀌어도 컴파일 에러 없이 리랭크 프롬프트가 조용히 비었다.
     */
    static String buildRerankPrompt(String question, List<MemoryCard> pool) {
        StringBuilder sb = new StringBuilder();
        sb.append("질문: ").append(question).append("\n\n근거 후보:\n");
        int n = 1;
        for (MemoryCard card : pool) {
            sb.append('[').append(n++).append("] ").append(card.title());
            if (!card.summary().isBlank()) {
                sb.append(" — ").append(card.summary());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 질문 + 번호 매긴 근거로 A(답변) 사용자 프롬프트를 만든다. 근거 콘텐츠는 마스킹된 원문에서 추출된 것이다.
     *
     * <p>각 근거의 <b>내용은 유형별 전략</b>({@link AnswerContribution#render})이 만든다 — 유형마다 어떤 필드가 근거인지 다르기
     * 때문이다(지식=사실, 트러블슈팅=증상·시도·원인·해결). 공유 코드는 질문·번호·순서만 담당한다(architecture.md 가드레일 2: 유형별 필드를 공유 코드에
     * 하드코딩하지 않는다).
     */
    String buildEvidencePrompt(String question, List<Memory> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("질문: ").append(question).append("\n\n근거:\n");
        int n = 1;
        for (Memory m : candidates) {
            sb.append('[').append(n++).append("] ").append(render(m)).append('\n');
        }
        return sb.toString();
    }
}
