package com.recall.memory.type;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Optional;

/**
 * 구조화 카드의 공통 계약 — 유형별 카드 record({@code KnowledgeCard}·{@code TroubleshootingCard})가 구현한다.
 *
 * <p><b>왜 이 타입이 생겼나</b> — SPI 경계가 {@code Map<String,Object>}였고, 그래서 타입 안전성이 유형 패키지 밖에서 끊겼다. 카드를 만드는
 * 곳(유형 전략)과 읽는 곳이 서로 다른 모듈이라, 같은 필드 이름을 여러 모듈이 문자열로 다시 적고 있었다: {@code MemoryService.cardStatus}는
 * {@code "status"}, {@code ReviewService.keywordText}는 {@code "title"}·{@code "summary"}·{@code
 * "keywords"}, {@code QueryPipeline.buildRerankPrompt}는 {@code "title"}·{@code "summary"}. 결과적으로
 * <b>카드 필드 이름을 바꿔도 컴파일 에러가 나지 않고</b> 런타임에 조용히 빈 값이 됐다. 또 Map 은 값 타입도 안 지켜서 읽는 쪽마다 {@code instanceof
 * List<?>}·{@code instanceof Map<?,?>} 방어를 다시 썼다(카드 record 가 이미 정규화한 값인데도).
 *
 * <p><b>왜 {@code sealed}이 아닌가</b> — 이 저장소엔 {@code module-info.java}가 없어 unnamed module 이고, 그 경우 JLS
 * 는 permitted subclass 가 <b>같은 패키지</b>에 있기를 요구한다(카드를 유형 패키지에 두는 규약과 충돌). 더 본질적으로 {@code sealed}의
 * 효용은 공유 코드에서의 exhaustive switch 인데, 그건 backend/CLAUDE.md 가 금지한 패턴이다("공유 코드에 {@code
 * switch(MemoryType)} 금지"). 여기서 sealed 는 금지된 방향을 유도하므로 쓰지 않는다.
 *
 * <p>공유 코드가 알아도 되는 필드만 계약에 올린다 — {@code title}·{@code summary}·{@code keywords}는 승인 시 {@code
 * memory.title}·BM25 색인이 실제로 읽는 값이라 유형과 무관하게 필요하다. 유형별 필드(symptom·facts 등)는 계약에 없고, 유형 전략만 자기 카드로
 * 다운캐스트해서 읽는다.
 */
public interface MemoryCard {

    /** 카드 제목. 승인 시 {@code memory.title}(NOT NULL)이 되므로 구현은 null 을 반환하지 않는다. */
    String title();

    /** 요약. 값이 없으면 빈 문자열(널 전파 차단). */
    String summary();

    /** 키워드 — BM25 정확 토큰 매칭 대상. 값이 없으면 빈 리스트. */
    List<String> keywords();

    /**
     * <b>카드 내용</b>의 상태(트러블슈팅의 해결 여부 등). 그런 상태를 정의하지 않는 유형은 {@link Optional#empty()}.
     *
     * <p>전에는 공유 코드가 {@code structured}의 {@code "status"} 키를 그대로 읽었다. 그건 {@code "status"}라는 이름을
     * <b>모든 유형에 예약</b>해 버리는 규약이었고, 자기 의미의 {@code status}를 가진 유형이 붙으면 목록 배지가 그 값을 트러블슈팅 해결상태로 오해하게
     * 된다. 옵트인 메서드로 바꿔 "이 유형은 내용 상태를 정의한다"를 카드가 명시적으로 선언하게 한다.
     *
     * <p>{@code @JsonIgnore} — 이 값은 카드 필드의 파생이지 별도 필드가 아니다(직렬화되면 {@code structured}에 중복 키가 생긴다).
     */
    @JsonIgnore
    default Optional<String> contentStatus() {
        return Optional.empty();
    }
}
