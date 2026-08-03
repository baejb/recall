package com.recall.capture;

import com.recall.capture.dto.CaptureRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원문 저장 서비스. 마스킹 우선 → 원문(근거) 커밋(유실 금지 앵커) → 저장 방송. */
@Service
public class CaptureService {

    private final CaptureRepository captureRepository;
    private final MaskingService maskingService;
    private final ApplicationEventPublisher events;

    public CaptureService(
            CaptureRepository captureRepository,
            MaskingService maskingService,
            ApplicationEventPublisher events) {
        this.captureRepository = captureRepository;
        this.maskingService = maskingService;
        this.events = events;
    }

    @Transactional
    public Long capture(CaptureRequest request) {
        String sourceType = request.sourceType() == null ? "chat" : request.sourceType();
        MaskingService.MaskResult masked = maskingService.mask(request.rawText()); // 마스킹 우선
        Capture saved =
                captureRepository.save(
                        new Capture(sourceType, masked.maskedText(), masked.maskedSpansJson()));
        // 이후 처리(추출·판정·검토대기함)는 store 모듈이 이 방송을 구독해 맡는다(모듈 순환 차단).
        events.publishEvent(new CaptureCreatedEvent(saved.getId(), masked.maskedText()));
        return saved.getId();
    }
}
