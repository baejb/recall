package com.recall.memory.type.knowledge;

import com.recall.memory.type.MemoryCard;
import java.util.List;

/**
 * 지식(knowledge) 유형의 구조화 스키마(S2 추출 결과)의 단일 기준점. PRD의 knowledge 필드
 * (topic·summary·keywords·facts·document)에 대응한다.
 *
 * <p>SPI 경계({@link com.recall.memory.type.ExtractionStrategy})가 {@link MemoryCard}를 쓰므로 이 레코드가 그
 * 계약을 구현한다 — 공유 코드는 {@code title}·{@code summary}·{@code keywords}만 보고, knowledge 고유 필드 ({@code
 * facts}·{@code document})는 knowledge 전략만 다운캐스트해서 읽는다.
 *
 * @param title 카드 제목(PRD의 topic 역할). {@code memory.title} 및 {@code structured}의 {@code "title"} 키
 * @param summary 요약({@code memory.summary})
 * @param keywords 키워드(search_tsv/BM25 보조)
 * @param facts 사실 항목(kind='fact' 임베딩·S4 대조)
 * @param document 정리된 본문(kind='document' 임베딩)
 */
public record KnowledgeCard(
        String title, String summary, List<String> keywords, List<String> facts, String document)
        implements MemoryCard {

    /**
     * null 리스트·문자열을 빈 값으로 정규화(널 전파 차단). Jackson 역직렬화도 이 생성자를 거친다.
     *
     * <p>문자열 정규화를 추가한 이유: {@link MemoryCard#title()}·{@link MemoryCard#summary()}가 공유 계약이 되면서 공유
     * 코드가 null 검사 없이 쓰게 됐다. 전에는 {@code title}이 null 로 남을 수 있었고, 그걸 읽는 쪽 ({@code ReviewService}의
     * JSON 파싱)이 각자 기본값을 붙이고 있었다 — 정규화를 스키마 한 곳으로 모은다. 제목이 아예 없을 때의 표시용 기본값("(제목 없음)")은 저장 시점의 판단이라
     * 승인 경로에 남긴다.
     */
    public KnowledgeCard {
        title = text(title);
        summary = text(summary);
        document = text(document);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    private static String text(String raw) {
        return raw == null ? "" : raw.strip();
    }
}
