package com.recall.review.controller;

import com.recall.common.exception.ValidationException;
import com.recall.common.web.ApiResponse;
import com.recall.review.controller.dto.ApprovedResponse;
import com.recall.review.controller.dto.PendingCountResponse;
import com.recall.review.controller.dto.ReviewItemResponse;
import com.recall.review.service.ReviewService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검토 대기함 입구 — 승인 게이트(불변 원칙: 승인 전에는 memory 에 쓰지 않는다)의 HTTP 표면.
 *
 * <p>컨트롤러는 HTTP 변환만 한다: 소유자 스코프·상태 검증·인덱싱은 서비스가 판정하고, 예외 → 에러 응답 변환은 전역 핸들러 한 곳이 담당한다.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** {@code ?view=} 로 받는 목록 구분 — 값이 화면 탭과 1:1 이다. */
    private static final String VIEW_PENDING = "pending";

    private static final String VIEW_PROCESSED = "processed";

    /**
     * 검토 항목 목록 — {@code ?view=pending}(기본) · {@code ?view=processed}.
     *
     * <p>기본값을 {@code pending} 으로 둬 기존 호출({@code GET /api/reviews})의 의미를 바꾸지 않는다.
     *
     * <p>모르는 값은 400 으로 거절한다. 기본값으로 조용히 흘리면 오타 하나에 대기 목록이 나오고, 그게 "처리 기록이 비어 있다"와 화면에서 구별되지 않는다(조용한
     * 실패 금지).
     */
    @GetMapping
    public ApiResponse<List<ReviewItemResponse>> list(
            @RequestParam(defaultValue = VIEW_PENDING) String view) {
        return switch (view) {
            case VIEW_PENDING -> ApiResponse.ok(reviewService.listPending());
            case VIEW_PROCESSED -> ApiResponse.ok(reviewService.listProcessed());
            default ->
                    throw new ValidationException(
                            "view 는 %s 또는 %s 여야 합니다".formatted(VIEW_PENDING, VIEW_PROCESSED),
                            "view");
        };
    }

    /** 승인 대기 건수(배지 등). */
    @GetMapping("/count")
    public ApiResponse<PendingCountResponse> count() {
        return ApiResponse.ok(new PendingCountResponse(reviewService.countPending()));
    }

    /** 승인 → memory 생성. */
    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovedResponse> approve(@PathVariable Long id) {
        return ApiResponse.ok(new ApprovedResponse(reviewService.approve(id)));
    }

    /**
     * 반려(삭제 아님, 상태만 전이).
     *
     * <p>돌려줄 값이 없다 — 전에는 {@code Map.of("status", "rejected")} 로 방금 요청한 동작을 되풀이해 알려 줬다. 성공했다는 사실은 공통
     * 형식의 {@code success} 가 이미 말한다.
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id) {
        reviewService.reject(id);
        return ApiResponse.ok();
    }
}
