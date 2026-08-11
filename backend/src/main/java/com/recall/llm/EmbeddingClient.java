package com.recall.llm;

/**
 * 임베딩 생성 포트(BYO key). 검색 표현(SearchRepresentation)이 만든 텍스트를 벡터로 바꾼다. provider·모델 교체를 위해 인터페이스로 연다.
 *
 * <p>저장(문서)과 조회(질의)는 임베딩 목적이 달라 분리한다(Voyage 등은 input_type으로 검색 품질이 오른다). 폴백 stub은 둘 다 같은 0벡터를 낸다.
 */
public interface EmbeddingClient {

    /** 임베딩 차원. 마이그레이션의 {@code vector(N)} 과 일치해야 한다. */
    int dimension();

    /** 저장 대상(문서) 텍스트 → 임베딩 벡터(길이 = {@link #dimension()}). */
    float[] embedDocument(String text);

    /** 조회 질의 텍스트 → 임베딩 벡터(길이 = {@link #dimension()}). */
    float[] embedQuery(String text);
}
