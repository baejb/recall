package com.recall.llm;

/**
 * 임베딩 생성 포트(BYO key). 검색 표현(SearchRepresentation)이 만든 텍스트를 벡터로 바꾼다. provider·모델 교체를 위해 인터페이스로 연다.
 */
public interface EmbeddingClient {

    /** 임베딩 차원. 마이그레이션의 {@code vector(N)} 과 일치해야 한다. */
    int dimension();

    /** 단일 텍스트 → 임베딩 벡터(길이 = {@link #dimension()}). */
    float[] embed(String text);
}
