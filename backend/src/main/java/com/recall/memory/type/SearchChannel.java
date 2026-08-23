package com.recall.memory.type;

/**
 * 하이브리드 검색의 융합 채널 — {@link PlanContribution}이 가중치를 주는 대상이자 {@code HybridSearchService}가 순위 리스트를 내는
 * 대상.
 *
 * <p><b>왜 enum 인가</b>(kind 와 다르게) — 채널은 <b>닫힌 집합</b>이다. 채널을 하나 추가한다는 건 검색기(R)에 실제 질의 경로를 구현한다는 뜻이므로
 * 공유 코드가 반드시 바뀐다. 반대로 문자열 키로 두면 {@code RrfFusion.fuse}가 모르는 키를 {@code getOrDefault(1.0)}으로 <b>조용히
 * 삼켜서</b>, 채널 이름 오타 하나로 유형별 가중치 설계 전체가 무효화되고 로그도 남지 않았다(전략이 준 {@code "memory_bm25"}와 서비스가 쓰는 이름이 각자
 * 리터럴이었다). enum 으로 두면 그 오타가 컴파일 에러가 된다.
 *
 * <p>PRD가 정의한 {@code exact}·{@code raw_bm25}·{@code raw_vector}는 아직 검색기에 구현되지 않았으므로 여기 넣지 않는다 —
 * 구현되지 않은 채널에 가중치를 주면 융합에서 무시돼 "설정했는데 안 먹는" 상태가 된다(조용한 실패 금지).
 */
public enum SearchChannel {

    /** 의미 유사(pgvector 코사인) 채널. */
    MEMORY_VECTOR,

    /** 정확 토큰 매칭(BM25 유사, {@code memory.search_tsv}) 채널. */
    MEMORY_BM25
}
