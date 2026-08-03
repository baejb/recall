package com.recall.review;

import com.recall.review.dto.ReviewItemResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 검토 대기함 입구. */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 승인 대기 목록. */
    @GetMapping
    public List<ReviewItemResponse> pending() {
        return reviewService.listPending();
    }

    /** 승인 대기 건수(배지 등). */
    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("pending", reviewService.countPending());
    }

    /** 승인 → memory 생성. */
    @PostMapping("/{id}/approve")
    public Map<String, Long> approve(@PathVariable Long id) {
        return Map.of("memoryId", reviewService.approve(id));
    }

    /** 반려(삭제 아님, 상태만 전이). */
    @PostMapping("/{id}/reject")
    public Map<String, String> reject(@PathVariable Long id) {
        reviewService.reject(id);
        return Map.of("status", "rejected");
    }
}
