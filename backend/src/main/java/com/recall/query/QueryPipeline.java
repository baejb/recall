package com.recall.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.Memory;
import com.recall.memory.type.AnswerContribution;
import com.recall.query.dto.AnswerFragment;
import com.recall.search.HybridSearchService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryPipeline(
            HybridSearchService searchService, List<AnswerContribution> answerContributions) {
        this.searchService = searchService;
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
            MemoryType type = parseType(llmClient.complete(CLASSIFY_SYSTEM, question));
            return supported.contains(type) ? type : fallback; // 미지원 유형이면 격하
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
        return searchService.search(question, type, ctx);
    }

    /** LLM 분류 출력에서 유형을 뽑는다. 트러블슈팅 신호가 없으면 기본 KNOWLEDGE. */
    static MemoryType parseType(String out) {
        if (out == null) {
            return MemoryType.KNOWLEDGE;
        }
        String u = out.toUpperCase();
        if (u.contains("TROUBLE") || out.contains("트러블") || out.contains("트슈")) {
            return MemoryType.TROUBLESHOOTING;
        }
        return MemoryType.KNOWLEDGE;
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
        LlmClient llmClient = ctx.requireChat();
        try {
            String out =
                    llmClient.complete(
                            RERANK_SYSTEM, buildRerankPrompt(question, pool, objectMapper));
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
                .map(
                        m ->
                                new AnswerFragment(
                                        answers.get(m.getType()).render(parse(m.getStructured())),
                                        m.getId()))
                .toList();
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

    /** 질문 + 번호 매긴 후보(제목·요약)로 RR 사용자 프롬프트를 만든다. */
    static String buildRerankPrompt(String question, List<Memory> pool, ObjectMapper mapper) {
        StringBuilder sb = new StringBuilder();
        sb.append("질문: ").append(question).append("\n\n근거 후보:\n");
        int n = 1;
        for (Memory m : pool) {
            Map<String, Object> s = parseWith(mapper, m.getStructured());
            sb.append('[').append(n++).append("] ");
            Object title = s.get("title");
            if (title != null) {
                sb.append(title);
            }
            Object summary = s.get("summary");
            if (summary != null) {
                sb.append(" — ").append(summary);
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
            sb.append('[').append(n++).append("] ");
            sb.append(answers.get(m.getType()).render(parse(m.getStructured())));
            sb.append('\n');
        }
        return sb.toString();
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
