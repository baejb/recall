package com.recall.memory.type;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 저장된 구조화 카드({@code memory.structured} · {@code review_queue.proposed} JSON)와 {@link MemoryCard}
 * 사이의 유일한 변환 창구.
 *
 * <p><b>왜 한곳으로 모으나</b> — 전에는 카드를 다루는 클래스마다 {@code new ObjectMapper()}를 필드로 만들어(백엔드 전체 11곳) 각자
 * {@code Map<String,Object>}로 읽었다. 두 가지가 따라왔다: (1) 관용 설정이 갈렸다 — 5곳만 {@code
 * FAIL_ON_UNKNOWN_PROPERTIES=false}를 켜서 같은 JSON을 클래스에 따라 다르게 읽을 여지가 있었고, (2) Map 으로 읽으니 <b>카드 생성자의
 * 정규화를 건너뛰었다</b>. 그래서 예컨대 S3 긴맥락 병합이 만든 카드는 status·outcome 정규화와 error_signature→keywords 병합을 한 번도
 * 통과하지 않고 DB·API까지 흘렀다. 이 코덱을 지나면 <b>되읽기가 항상 유형 스키마를 거친다</b>.
 *
 * <p>유형 → 카드 클래스 매핑은 {@link ExtractionStrategy#cardType()}에서 온다 — 공유 코드가 유형별 카드 클래스를 알지 않게 하면서도 타입
 * 있는 역직렬화를 할 수 있게 하는 유일한 연결점이다.
 *
 * <p>관용 설정({@code FAIL_ON_UNKNOWN_PROPERTIES=false})은 <b>되읽기</b>라 의도적이다: 이미 저장된 카드에 나중에 필드가 생기거나
 * 빠져도 조회가 깨지지 않아야 한다(스키마 진화). 대신 값 정규화는 카드 생성자가 강제한다.
 */
@Component
public class CardCodec {

    private static final Logger log = LoggerFactory.getLogger(CardCodec.class);

    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StrategyRegistry<ExtractionStrategy> extractions;

    public CardCodec(List<ExtractionStrategy> extractionStrategies) {
        this.extractions = new StrategyRegistry<>(extractionStrategies);
        logCoverage();
    }

    /**
     * 부팅 시 추출 전략 커버리지를 드러낸다.
     *
     * <p><b>왜 fail-fast 가 아닌가</b> — 유형이 부분적으로만 구현된 상태는 정상이다({@code TypeClassifier} 가 등록된 유형만 분류
     * 허용목록으로 쓴다). 그래서 부팅을 막지는 않는다. 대신 <b>전략 없는 유형을 부팅 로그에 남긴다</b>: 그 유형으로 이미 저장된 카드가 있으면 첫 읽기에서야
     * 500 으로 알게 되는데, 그때는 어떤 배포가 원인인지 되짚기 어렵다.
     */
    private void logCoverage() {
        Set<MemoryType> covered = extractions.registered();
        Set<MemoryType> missing =
                Arrays.stream(MemoryType.values())
                        .filter(type -> !covered.contains(type))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        log.info("추출 전략 등록 유형 {}", covered);
        if (!missing.isEmpty()) {
            log.warn("추출 전략이 없는 유형 {} — 이 유형으로 저장된 카드는 읽을 수 없다(읽기 시 500).", missing);
        }
    }

    /**
     * 저장된 JSON → 유형 카드. 실패는 예외로 드러낸다(조용한 실패 금지).
     *
     * <p><b>전략 조회를 try 밖에 두는 이유</b> — 안에 두면 "이 유형에 전략이 등록되지 않았다"(배선 결함)가 "이 JSON이 깨졌다"(데이터 한 건)와
     * <b>같은 예외로 감싸져</b> {@link #readOrNull} 의 격하에 함께 걸린다. 두 실패의 범위가 다르다: 후자는 그 한 건이지만 전자는 <b>그 유형의
     * 모든 카드</b>다. 자세한 결말은 {@link CardUnreadableException}.
     */
    public MemoryCard read(MemoryType type, String json) {
        Class<? extends MemoryCard> cardType = extractions.get(type).cardType();
        try {
            return objectMapper.readValue(json, cardType);
        } catch (Exception e) {
            throw new CardUnreadableException(type, e);
        }
    }

    /**
     * {@link #read(MemoryType, String)} 의 격하 버전 — 실패하면 던지지 않고 {@code null}.
     *
     * <p><b>왜 필요한가</b> — 이 코덱의 관용 설정은 unknown/누락 필드에만 관용이고 <b>값 모양 불일치는 그대로 던진다</b>(예: {@code
     * attempts} 가 객체 배열이 아니라 문자열로 저장된 레거시 행 — 이 클래스 javadoc이 인정하는, 정규화를 거치지 않고 저장된 S3 병합 카드가 정확히 그
     * 후보다). 그런 행이 <b>한 건</b> 있으면 조회·승인·저장·재색인 네 경로가 연쇄로 죽었다: 답변 경로는 격하 장치(fallbackFragments)가 같은
     * read 로 또 던져 질의 전체가 에러가 되고, 재색인은 루프가 통째로 FAILED 가 되어 {@code embedding_status=FAILED} → 그 사용자의
     * <b>벡터 채널이 전부 꺼졌다</b>.
     *
     * <p>그래서 "한 건을 건너뛰고 나머지를 살릴 수 있는" 호출부는 이 메서드를 쓴다. 반대로 그 카드가 <b>작업의 대상 자체</b>인 곳(승인)은 {@link
     * #read} 를 쓰고 예외로 드러낸다 — 건너뛸 대상이 없으니 격하가 의미 없다.
     *
     * <p>{@code null} 을 조용히 흘리지 않도록 호출부가 로그를 남기는 것이 규약이다(조용한 실패 금지).
     *
     * <p><b>격하 범위는 {@link CardUnreadableException} 하나뿐이다.</b> 전에는 {@code RuntimeException} 을 잡아 "전략
     * 미등록"(배선 결함)까지 {@code null} 로 만들었고, 그러면 재색인이 그 유형의 임베딩을 지우고도 잡을 성공으로 마쳤다. 배선 결함은 건너뛸 대상이 아니라
     * 500 으로 드러날 결함이므로 그대로 전파시킨다.
     */
    public MemoryCard readOrNull(MemoryType type, String json) {
        try {
            return read(type, json);
        } catch (CardUnreadableException e) {
            return null;
        }
    }

    /**
     * 필드 맵 → 유형 카드(정규화가 카드 생성자에서 강제된다). 전략 조회는 {@link #read(MemoryType, String)} 과 같은 이유로 try 밖.
     */
    public MemoryCard read(MemoryType type, Map<String, Object> fields) {
        Class<? extends MemoryCard> cardType = extractions.get(type).cardType();
        try {
            return objectMapper.convertValue(fields, cardType);
        } catch (Exception e) {
            throw new CardUnreadableException(type, e);
        }
    }

    /** 카드 → 저장용 JSON({@code structured}/{@code proposed} 컬럼). */
    public String writeJson(MemoryCard card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            throw new IllegalStateException("구조화 카드 직렬화 실패", e);
        }
    }

    /**
     * 카드 → 필드 맵.
     *
     * <p>맵이 필요한 자리는 <b>스키마를 몰라도 동작해야 하는</b> S3 결정론 병합뿐이다. 그 외에는 카드 접근자를 쓴다.
     *
     * <p>주의: 이 변환은 <b>현재 카드 record 가 아는 필드만</b> 낸다. 저장된 JSON 을 있는 그대로 내보내야 하는 자리에는 {@link
     * #readRaw(String)} 를 쓴다 — 그 구분을 놓쳐서 실제로 응답 계약이 깨진 적이 있다(아래 참조).
     */
    public Map<String, Object> toMap(MemoryCard card) {
        return objectMapper.convertValue(card, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 저장된 JSON을 <b>있는 그대로</b> 필드 맵으로 읽는다(카드 record 를 거치지 않는다).
     *
     * <p><b>왜 별도 통로인가</b> — {@code MemoryDetailResponse.structured} 는 "승인된 카드 전체를 유형과 무관하게 그대로
     * 싣는다"가 계약이다. 그런데 카드 타입으로 되읽은 뒤 다시 맵으로 바꾸면(read → toMap) 그 왕복이 <b>현재 record 에 없는 필드를 조용히
     * 떨어뜨린다</b>: 코덱이 스키마 진화를 위해 unknown 필드를 무시하도록 설정돼 있으니, 예전에 저장된 필드나 나중에 추가될 필드가 응답에서 사라진다. 필드를
     * <b>읽을</b> 때는 타입이 맞고(오타·스키마 변경을 컴파일이 잡는다), 그대로 <b>통과시킬</b> 때는 원본이 맞다 — 두 목적에 같은 경로를 쓰면 안 된다.
     */
    public Map<String, Object> readRaw(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("구조화 카드 원본 파싱 실패", e);
        }
    }
}
