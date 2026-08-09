package com.recall.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 0 walking skeleton용 임베딩 stub. 실제 어댑터가 없을 때 {@link LlmConfig}가 기본 빈으로 등록한다. 0으로 채운 고정 차원 벡터를
 * 반환하되 stub 관여를 로그로 남긴다(조용한 실패 금지).
 */
public class StubEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(StubEmbeddingClient.class);

    /** V2 마이그레이션의 vector(1024)와 맞춘 기본 차원(voyage-3 기준). */
    private static final int DIMENSION = 1024;

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public float[] embed(String text) {
        log.warn("[STUB] EmbeddingClient.embed 호출 — 실제 임베딩 미연동, 0벡터 반환");
        return new float[DIMENSION];
    }
}
