package com.recall.settings.service.entity;

/**
 * 사용자별 임베딩 인덱스 상태({@code model_setting.embedding_status}) 어휘 — 이 컬럼을 소유한 settings 도메인이 어휘도 소유한다.
 *
 * <p>전에는 어휘를 쓰는 세 곳이 모두 자기 리터럴을 갖고 있었다: {@code SettingsService}가 {@code "REINDEXING"}, {@code
 * ReindexService}가 {@code "READY"}·{@code "FAILED"}, {@code HybridSearchService}가 자기 private 상수
 * {@code STATUS_READY}. 세 곳이 서로 다른 모듈이라 어휘 하나만 어긋나도 <b>벡터 채널이 조용히 꺼지거나 켜진다</b>.
 *
 * <p>이 상태가 검색 동작을 바꾸는 이유: {@link #REINDEXING}(신구 모델 혼재)과 {@link #FAILED}(재색인 중간 실패로 혼재)에서는 벡터 공간이
 * 일관되지 않아 벡터 채널을 격하해야 한다. 즉 <b>{@link #READY}만 벡터 채널이 안전한 상태</b>다.
 */
public final class EmbeddingStatus {

    /** 인덱스가 현재 임베딩 모델과 일관 — 벡터 채널을 쓸 수 있는 유일한 상태. */
    public static final String READY = "READY";

    /** 재색인 진행 중 — 신구 모델 벡터가 섞여 있다. */
    public static final String REINDEXING = "REINDEXING";

    /** 재색인이 중간에 실패 — 신구 모델 벡터가 섞인 채로 남았다. */
    public static final String FAILED = "FAILED";

    private EmbeddingStatus() {}
}
