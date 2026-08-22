package com.recall.memory.type;

/**
 * 임베딩 지문 종류({@code memory_embedding.kind}) 어휘 — 이 이름을 쓰는 도메인(유형별 검색 표현)이 소유한다.
 *
 * <p><b>왜 모으나</b> — 같은 문자열이 서로 다른 세 네임스페이스에 흩어져 있었고 무엇도 그들을 구분하지 않았다. 특히 {@code "document"}는 (1) 이
 * kind, (2) knowledge 카드의 필드 이름({@code KnowledgeCard.document}), (3) Voyage API 의 input type
 * ({@code VoyageEmbeddingClient})으로 <b>동시에</b> 쓰였다. 셋이 우연히 같은 문자열이라 {@code SimilarMemoryFinder}의
 * "document kind 는 문서 vs 문서 대조" 특수 취급이 어떤 네임스페이스를 의도한 것인지 코드만 봐선 알 수 없었다.
 *
 * <p><b>왜 enum 이 아닌 String 상수인가</b> — kind 는 <b>열린 집합</b>이다. architecture.md 가드레일 3이 "새 지문 종류 =
 * (스키마 변경이 아니라) 행 추가로 끝"을 요구하고 DB 컬럼도 {@code VARCHAR(32)}로 열려 있다. 공유 코드에 닫힌 enum 을 두면 새 유형이 자기 kind
 * 를 쓰려면 공유 코드를 고쳐야 해 OCP 가 깨진다. 그래서 <b>알려진 kind 를 한곳에 문서화</b>하되 집합을 닫지는 않는다.
 */
public final class EmbeddingKind {

    /** 트러블슈팅 — 증상·에러·시그니처·환경. "이런 증상이었는데" 류 질문이 걸리는 쪽. */
    public static final String PROBLEM = "problem";

    /** 트러블슈팅 — 근본 원인·최종 해결. "결국 어떻게 고쳤지" 류 질문이 걸리는 쪽. */
    public static final String SOLUTION = "solution";

    /**
     * 지식 — 정리된 본문. S4 유사 판정의 "문서 vs 문서" 대조 대상이기도 하다({@code SimilarMemoryFinder}가 이 kind 를 대표로
     * 우선한다).
     */
    public static final String DOCUMENT = "document";

    /** 지식 — 사실 항목을 합친 지문. */
    public static final String FACT = "fact";

    private EmbeddingKind() {}
}
