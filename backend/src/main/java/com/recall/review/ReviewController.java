package com.recall.review;

import com.recall.review.dto.ReviewItem;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public List<ReviewItem> pending() {
        return reviewService.pending().stream().map(ReviewItem::from).toList();
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("pending", reviewService.pendingCount());
    }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable Long id) {
        reviewService.approve(id);
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable Long id) {
        reviewService.reject(id);
    }
}
