package com.recall.review.service.entity;

import java.util.List;

/**
 * 검토 항목 상태({@code review_queue.status}) 어휘 — 이 컬럼을 소유한 review 도메인이 어휘도 소유한다.
 *
 * <p>전에는 {@code ReviewService}가 {@code "pending"}·{@code "approved"}·{@code "rejected"}를 리터럴로 다섯 번,
 * {@code ReviewItem}이 필드 초기화로 한 번, {@code ReviewController}가 응답 바디에 한 번 적었다. 승인 게이트의 상태 어휘는 불변 원칙
 * ("자동 저장 없음 · 승인 게이트")의 표현이므로 한곳에서 관리한다.
 *
 * <p>{@code edited}는 스키마 주석에만 있고 코드 경로가 없어 넣지 않는다 — 쓰이지 않는 상수를 두면 "지원한다"로 읽힌다.
 */
public final class ReviewStatus {

    /** 승인 대기 — 아직 memory 에 반영되지 않았다. */
    public static final String PENDING = "pending";

    /** 승인됨 — 이 시점에만 memory 가 만들어진다. */
    public static final String APPROVED = "approved";

    /** 반려됨 — memory 를 만들지 않고 항목만 닫는다(삭제 아님, 상태 보존). */
    public static final String REJECTED = "rejected";

    /**
     * 이미 처리돼 대기함을 떠난 상태들 — 처리 기록 조회의 대상.
     *
     * <p>호출부마다 {@code List.of(APPROVED, REJECTED)} 를 적지 않고 여기서 한 번에 묶는 이유: 상태가 늘면 그것이 대기 쪽인지 처리 쪽인지
     * 정하는 자리가 하나여야 한다. 흩어져 있으면 새 상태가 어느 목록에도 안 들어가 <b>어느 화면에서도 조회되지 않는 항목</b>이 조용히 생긴다.
     */
    public static final List<String> RESOLVED = List.of(APPROVED, REJECTED);

    private ReviewStatus() {}
}
