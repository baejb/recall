package com.recall.memory.type.knowledge;

import java.util.List;

/**
 * 지식(knowledge) 유형의 구조화 스키마(S2 추출 결과)의 단일 기준점. PRD의 knowledge 필드
 * (topic·summary·keywords·facts·document)에 대응한다.
 *
 * <p>SPI 경계({@link com.recall.memory.type.ExtractionStrategy})는 {@code Map<String,Object>}를 쓰므로, 이
 * 레코드는 knowledge 패키지 내부의 타입 안전 표현으로만 쓰고 Jackson으로 Map과 변환한다.
 *
 * @param title 카드 제목(PRD의 topic 역할). {@code memory.title} 및 승인 시 읽는 {@code "title"} 키에 대응
 * @param summary 요약({@code memory.summary})
 * @param keywords 키워드(후속: search_tsv/BM25 보조)
 * @param facts 사실 항목(후속: kind='fact' 임베딩·S4 대조)
 * @param document 정리된 본문(후속: kind='document' 임베딩)
 */
public record KnowledgeCard(
        String title, String summary, List<String> keywords, List<String> facts, String document) {

    /** null 리스트는 빈 리스트로 정규화(널 전파 차단). Jackson 역직렬화도 이 생성자를 거친다. */
    public KnowledgeCard {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
