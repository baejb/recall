package com.recall.review.controller;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * 검토 대기함 입구 — 승인 게이트(불변 원칙: 승인 전에는 memory 에 쓰지 않는다)의 HTTP 표면.
 *
 * <p>컨트롤러는 HTTP 변환만 한다: 소유자 스코프·상태 검증·인덱싱은 서비스가 판정하고, 예외 → 에러 봉투 변환은 전역 핸들러 한 곳이 담당한다.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 승인 대기 목록. */
    @GetMapping
    public ApiResponse<List<ReviewItemResponse>> pending() {
        return ApiResponse.ok(reviewService.listPending());
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
     * <p>돌려줄 값이 없다 — 전에는 {@code Map.of("status", "rejected")} 로 방금 요청한 동작을 되풀이해 알려 줬다. 성공했다는 사실은
     * 봉투의 {@code success} 가 이미 말한다.
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id) {
        reviewService.reject(id);
        return ApiResponse.ok();
    }
}
