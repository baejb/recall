package com.recall.extraction;

import com.recall.review.Judgement;
import com.recall.review.ReviewService;
import com.recall.typerouter.ReentryJudge;
import com.recall.typerouter.TypeRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async store-path job: split → noise-filter → Map extract → Reduce merge (JSON-schema
 * enforced), then type-route and reentry-judge, and finally land the result in the review
 * queue as pending. Never writes to memory directly.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final TypeRouter typeRouter;
    private final ReentryJudge reentryJudge;
    private final ReviewService reviewService;

    public ExtractionService(TypeRouter typeRouter, ReentryJudge reentryJudge, ReviewService reviewService) {
        this.typeRouter = typeRouter;
        this.reentryJudge = reentryJudge;
        this.reviewService = reviewService;
    }

    @Async
    public void extract(Long captureId) {
        log.info("extraction job started for capture {}", captureId);

        // TODO: real Map-Reduce extraction against the capture text via the LLM.
        String extractionJson = "{}";

        typeRouter.route(extractionJson);
        Judgement judgement = reentryJudge.judge(extractionJson);

        reviewService.enqueue(captureId, judgement,
                "scaffold: extraction not implemented yet — placeholder pending item");
        log.info("extraction job enqueued review item for capture {}", captureId);
    }
}
