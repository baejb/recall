package com.recall.capture.service.entity;

/**
 * 원문 캡처의 처리 상태({@code capture.status}) 어휘 — 이 컬럼을 소유한 capture 도메인이 어휘도 소유한다.
 *
 * <p>전에는 {@code Capture} 엔티티가 초기값을, {@code CaptureService}가 조회 필터를, {@code StorePipeline}(store
 * 모듈)이 완료 전이를 각자 리터럴로 적었다 — 어휘를 쓰는 세 곳 중 둘이 이 도메인 밖이라 특히 흩어지기 쉬웠다.
 *
 * <p>이 어휘는 불변 원칙 6("조용한 실패/절단 금지")의 표현이다: 실패는 지우지 않고 {@link #FAILED} + {@code failed_stage}로 남긴다.
 */
public final class CaptureStatus {

    /** 저장 파이프라인이 아직 처리 중(원문은 이미 커밋됨). */
    public static final String PROCESSING = "PROCESSING";

    /** 검토 대기함까지 올라간 정상 완료. */
    public static final String DONE = "DONE";

    /** 어느 단계에서 실패 — 어느 단계였는지는 {@code failed_stage}에 남는다. */
    public static final String FAILED = "FAILED";

    private CaptureStatus() {}
}
