package com.recall.capture;

import com.recall.extraction.ExtractionService;
import com.recall.intent.IntentRouter;
import com.recall.intent.IntentType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store-path entry point: mask → persist original → (if a store intent) kick off async
 * extraction. Nothing is written to memory here — that only happens after review approval.
 */
@Service
public class CaptureService {

    private final MaskingService maskingService;
    private final CaptureRepository captureRepository;
    private final IntentRouter intentRouter;
    private final ExtractionService extractionService;

    public CaptureService(MaskingService maskingService,
                          CaptureRepository captureRepository,
                          IntentRouter intentRouter,
                          ExtractionService extractionService) {
        this.maskingService = maskingService;
        this.captureRepository = captureRepository;
        this.intentRouter = intentRouter;
        this.extractionService = extractionService;
    }

    @Transactional
    public Long capture(String text, String sourceType) {
        // 1) mask BEFORE anything leaves the process
        MaskingService.MaskResult masked = maskingService.mask(text);

        // 2) preserve the (masked) original as evidence
        Capture saved = captureRepository.save(new Capture(sourceType, masked.masked()));

        // 3) classify + hand off to the async extraction job
        IntentType intent = intentRouter.classify(masked.masked());
        if (intent == IntentType.STORE) {
            extractionService.extract(saved.getId());
        }
        return saved.getId();
    }
}
