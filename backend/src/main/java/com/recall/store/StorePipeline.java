package com.recall.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.capture.Capture;
import com.recall.capture.CaptureCreatedEvent;
import com.recall.capture.CaptureRepository;
import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.memory.type.ExtractionStrategy;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.SimilarityJudgeStrategy;
import com.recall.review.ReviewItem;
import com.recall.review.ReviewRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장 파이프라인: 원문 저장 후 추출(S2) → 판정(S4) → 검토 대기함에 올린다. 승인 전에는 memory에 반영하지 않는다(불변 원칙: 자동 저장 없음·승인 게이트).
 * 유형별 로직은 전략 레지스트리로 디스패치.
 */
@Component
public class StorePipeline {

    private static final Logger log = LoggerFactory.getLogger(StorePipeline.class);

    private final CaptureRepository captureRepository;
    private final ReviewRepository reviewRepository;
    private final StrategyRegistry<ExtractionStrategy> extractions;
    private final StrategyRegistry<SimilarityJudgeStrategy> judges;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StorePipeline(
            CaptureRepository captureRepository,
            ReviewRepository reviewRepository,
            List<ExtractionStrategy> extractionStrategies,
            List<SimilarityJudgeStrategy> judgeStrategies) {
        this.captureRepository = captureRepository;
        this.reviewRepository = reviewRepository;
        this.extractions = new StrategyRegistry<>(extractionStrategies);
        this.judges = new StrategyRegistry<>(judgeStrategies);
    }

    /** 원문 저장 방송을 받아 추출·판정 후 검토 대기함에 올린다. (Phase 1c: @Async로 비동기 전환) */
    @EventListener
    @Transactional
    public void onCaptureCreated(CaptureCreatedEvent event) {
        Capture capture =
                captureRepository
                        .findById(event.captureId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "capture 없음: " + event.captureId()));

        MemoryType type = classify(event.maskedText());
        Map<String, Object> structured = extractions.get(type).extract(event.maskedText()); // S2
        Judgement judgement = judges.get(type).judge(structured, Map.of()); // S4

        ReviewItem item = new ReviewItem(capture, judgement.verdict(), toJson(structured));
        reviewRepository.save(item);
        log.info(
                "검토 대기함 등록: capture={} type={} verdict={}",
                capture.getId(),
                type,
                judgement.verdict());
    }

    private MemoryType classify(String maskedText) {
        // TODO(Phase 1): 다차원 분류(C)로 유형 판정. 지금은 기본 KNOWLEDGE.
        return MemoryType.KNOWLEDGE;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("proposed 직렬화 실패", e);
        }
    }
}
