package com.recall.memory.type.troubleshooting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.recall.memory.type.MemoryCard;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 트러블슈팅(troubleshooting) 유형의 구조화 스키마(S2 추출 결과)의 단일 기준점. PRD §04의 troubleshooting 필드
 * (symptom·error_message·environment·attempts[]·root_cause·final_solution·status)에 대응하고, 공유 코드가 쓰는
 * title·summary·keywords를 함께 갖는다(승인 시 {@code memory.title}, BM25 {@code search_tsv}가 이 세 필드를 읽는다).
 *
 * <p>SPI 경계({@link com.recall.memory.type.ExtractionStrategy})는 {@code Map<String,Object>}를 쓰므로, 이
 * 레코드는 troubleshooting 패키지 내부의 타입 안전 표현으로만 쓰고 Jackson으로 Map과 변환한다(knowledge와 같은 규약).
 *
 * <p>JSON 키는 PRD 표기(snake_case)를 그대로 쓴다 — 프롬프트·프론트·검색 표현이 모두 같은 이름을 보게 해 매핑 실수를 줄인다.
 *
 * @param title 카드 제목(무슨 문제였나). {@code memory.title} 및 승인 시 읽는 {@code "title"} 키에 대응
 * @param summary 요약({@code memory.summary})
 * @param keywords 키워드. 에러 시그니처처럼 <b>정확히 일치해야 찾을 토큰</b>이 여기 들어간다(BM25 대상)
 * @param symptom 관찰된 증상(kind='problem' 임베딩 재료)
 * @param errorMessage 에러·로그 원문 조각(kind='problem' 임베딩 재료)
 * @param errorSignature 에러를 식별하는 정규화된 한 줄. keywords에도 반영해 정확 토큰 매칭을 노린다
 * @param environment 환경(OS·런타임·버전). 같은 에러라도 환경이 다르면 재발이 아닐 수 있어 S4 판정 근거가 된다
 * @param attempts 시도 이력. <b>실패한 시도도 버리지 않는다</b>(PRD: attempts 유실은 🟠 중대 실패)
 * @param rootCause 근본 원인(밝혀진 것만)
 * @param finalSolution 최종 해결책(kind='solution' 임베딩 재료)
 * @param status 해결 상태 — {@link #RESOLVED}·{@link #PARTIAL}·{@link #UNRESOLVED} 중 하나로 정규화된다
 */
public record TroubleshootingCard(
        String title,
        String summary,
        List<String> keywords,
        String symptom,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("error_signature") String errorSignature,
        String environment,
        List<Attempt> attempts,
        @JsonProperty("root_cause") String rootCause,
        @JsonProperty("final_solution") String finalSolution,
        String status)
        implements MemoryCard {

    /**
     * 트러블슈팅은 카드 내용의 상태(해결 여부)를 정의하는 유형이므로 공유 계약에 그 값을 노출한다.
     *
     * <p>공유 코드가 {@code structured}의 {@code "status"} 키를 직접 읽던 것을 대체한다 — 그 방식은 {@code "status"}라는
     * 이름을 모든 유형에 예약해, 자기 의미의 {@code status}를 가진 유형이 생기면 목록 배지가 그 값을 해결상태로 오해하게 만든다.
     */
    @Override
    public Optional<String> contentStatus() {
        return Optional.of(status);
    }

    /** 해결됨. */
    public static final String RESOLVED = "RESOLVED";

    /** 증상만 완화·우회. */
    public static final String PARTIAL = "PARTIAL";

    /** 미해결 — 모르는 상태 값의 기본값이기도 하다. */
    public static final String UNRESOLVED = "UNRESOLVED";

    private static final List<String> STATUSES = List.of(RESOLVED, PARTIAL, UNRESOLVED);

    /**
     * null·모르는 값을 정규화한다(널 전파 차단). Jackson 역직렬화도 이 생성자를 거친다.
     *
     * <p>status의 기본값을 {@link #UNRESOLVED}로 두는 것은 의도적이다 — 모델이 이상한 값을 주었을 때 "해결됐다"고 단정하는 쪽이 그 반대보다
     * 위험하다(근거 없는 생성 금지의 연장). 값이 버려진 게 아니라 보수적으로 해석됐을 뿐이며, 원문은 capture에 그대로 남는다.
     */
    public TroubleshootingCard {
        title = text(title);
        summary = text(summary);
        symptom = text(symptom);
        errorMessage = text(errorMessage);
        errorSignature = text(errorSignature);
        environment = text(environment);
        rootCause = text(rootCause);
        finalSolution = text(finalSolution);
        status = normalizeStatus(status);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        keywords = keywordsWithSignature(keywords, errorSignature);
    }

    /**
     * 한 번의 시도와 그 결말.
     *
     * @param action 시도한 조치
     * @param result 그 결과(관찰된 것)
     * @param outcome 판정 — {@link #FAILED}·{@link #PARTIAL}·{@link #WORKED}, 판단 불가면 {@link #UNKNOWN}
     */
    public record Attempt(String action, String result, String outcome) {

        /** 통하지 않음. */
        public static final String FAILED = "failed";

        /** 부분적으로 통함. */
        public static final String PARTIAL = "partial";

        /** 통함. */
        public static final String WORKED = "worked";

        /** 판정 불가 — 모르는 값을 {@link #FAILED}로 위장하지 않는다(조용한 실패 금지). */
        public static final String UNKNOWN = "unknown";

        private static final List<String> OUTCOMES = List.of(FAILED, PARTIAL, WORKED);

        public Attempt {
            action = text(action);
            result = text(result);
            outcome = normalizeOutcome(outcome);
        }

        private static String normalizeOutcome(String raw) {
            String normalized = text(raw).toLowerCase(Locale.ROOT);
            return OUTCOMES.contains(normalized) ? normalized : UNKNOWN;
        }
    }

    /** 에러 시그니처를 keywords에 반영한다 — 정확 토큰 매칭(BM25)이 벡터보다 강한 필드라 색인 대상에 반드시 남긴다. */
    private static List<String> keywordsWithSignature(List<String> keywords, String signature) {
        List<String> merged = new ArrayList<>();
        if (keywords != null) {
            keywords.stream().filter(k -> k != null && !k.isBlank()).forEach(merged::add);
        }
        if (!signature.isBlank() && merged.stream().noneMatch(k -> k.equalsIgnoreCase(signature))) {
            merged.add(signature);
        }
        return List.copyOf(merged);
    }

    private static String normalizeStatus(String raw) {
        String normalized = text(raw).toUpperCase(Locale.ROOT);
        return STATUSES.contains(normalized) ? normalized : UNRESOLVED;
    }

    /** null·공백 문자열을 빈 문자열로 정규화(널 전파 차단). */
    private static String text(String raw) {
        return raw == null ? "" : raw.strip();
    }
}
