package com.recall.capture;

import com.recall.capture.dto.CaptureRequest;
import com.recall.capture.dto.CaptureStatusResponse;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 원문 저장 서비스. 마스킹 우선 → 원문(근거) 커밋(유실 금지 앵커) → 저장 방송. */
@Service
public class CaptureService {

    private static final String DEFAULT_SOURCE_TYPE = "chat";

    private final CaptureRepository captureRepository;
    private final MaskingService maskingService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public CaptureService(
            CaptureRepository captureRepository,
            MaskingService maskingService,
            ApplicationEventPublisher events,
            PlatformTransactionManager txManager) {
        this.captureRepository = captureRepository;
        this.maskingService = maskingService;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    public Long capture(CaptureRequest request) {
        String sourceType =
                request.sourceType() == null ? DEFAULT_SOURCE_TYPE : request.sourceType();

        // 마스킹 우선(불변 원칙): DB를 건드리지 않는 결정론 단계라 트랜잭션 밖에서 처리해 커넥션 점유를 줄인다.
        MaskingService.MaskResult masked = maskingService.mask(request.rawText());

        // 트랜잭션은 원문 커밋(유실 금지 앵커) + 방송에만 건다.
        // 방송을 트랜잭션 안에서 하는 이유: store 리스너가 AFTER_COMMIT로 걸려 있어, 커밋이 확정된 뒤에만 이후 처리가 돈다.
        return tx.execute(
                status -> {
                    Capture saved =
                            captureRepository.save(
                                    new Capture(
                                            sourceType,
                                            masked.maskedText(),
                                            masked.maskedSpansJson()));
                    // 이후 처리(추출·판정·검토대기함)는 store 모듈이 이 방송을 구독해 맡는다(모듈 순환 차단).
                    events.publishEvent(
                            new CaptureCreatedEvent(saved.getId(), masked.maskedText()));
                    return saved.getId();
                });
    }

    /**
     * 아직 검토 대기함에 오르지 않은(처리 중이거나 실패한) 캡처를 최신순으로 노출한다. 조용한 실패 금지: FAILED 도 목록에 실려 UI 가 "정리 중/실패"를 보여줄
     * 수 있게 한다. 원문은 응답에 담지 않는다.
     */
    @Transactional(readOnly = true)
    public List<CaptureStatusResponse> activeCaptures() {
        return captureRepository
                .findByStatusInOrderByCreatedAtDesc(List.of("PROCESSING", "FAILED"))
                .stream()
                .map(
                        c ->
                                new CaptureStatusResponse(
                                        c.getId(),
                                        c.getStatus(),
                                        c.getSourceType(),
                                        c.getFailedStage(),
                                        c.getCreatedAt()))
                .toList();
    }
}
