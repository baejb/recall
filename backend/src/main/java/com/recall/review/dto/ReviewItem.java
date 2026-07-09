package com.recall.review.dto;

import com.recall.review.Judgement;
import com.recall.review.ReviewQueue;
import java.time.Instant;

public record ReviewItem(
        Long id,
        Long captureId,
        Judgement judgement,
        String judgeReason,
        String status,
        Instant createdAt
) {
    public static ReviewItem from(ReviewQueue r) {
        return new ReviewItem(r.getId(), r.getCaptureId(), r.getJudgement(),
                r.getJudgeReason(), r.getStatus(), r.getCreatedAt());
    }
}
