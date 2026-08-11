package com.recall.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.capture.Capture;
import com.recall.capture.CaptureCreatedEvent;
import com.recall.capture.CaptureRepository;
import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.memory.Memory;
import com.recall.memory.type.ExtractionStrategy;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.SimilarityJudgeStrategy;
import com.recall.memory.type.Verdict;
import com.recall.review.ReviewItem;
import com.recall.review.ReviewRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장 파이프라인: 원문 저장 후 추출(S2) → 판정(S4) → 검토 대기함에 올린다. 승인 전에는 memory에 반영하지 않는다(불변 원칙: 자동 저장 없음·승인 게이트).
 * 유형별 로직은 전략 레지스트리로 디스패치.
 */
@Component
public class StorePipeline {

    private static final Logger log = LoggerFactory.getLogger(StorePipeline.class);

    private final CaptureRepository captureRepository;
    private final ReviewRepository reviewRepository;
    private final SimilarMemoryFinder similarMemoryFinder;
    private final StrategyRegistry<ExtractionStrategy> extractions;
    private final StrategyRegistry<SimilarityJudgeStrategy> judges;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StorePipeline(
            CaptureRepository captureRepository,
            ReviewRepository reviewRepository,
            SimilarMemoryFinder similarMemoryFinder,
            List<ExtractionStrategy> extractionStrategies,
            List<SimilarityJudgeStrategy> judgeStrategies) {
        this.captureRepository = captureRepository;
        this.reviewRepository = reviewRepository;
        this.similarMemoryFinder = similarMemoryFinder;
        this.extractions = new StrategyRegistry<>(extractionStrategies);
        this.judges = new StrategyRegistry<>(judgeStrategies);
    }

    /**
     * 원문 커밋(앵커) 후 별도 스레드에서 추출·판정 → 검토 대기함에 올린다. 커밋 뒤에만 돌아 원문 유실 위험이 없고, 비동기라 저장 응답을 막지 않는다(경로 성격:
     * 저장=비동기).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        // S4 — 유사 기존 기억을 찾아 대조 판정. 후보가 없으면 빈 맵을 넘겨 NEW로 귀결.
        Optional<Memory> similar = similarMemoryFinder.findSimilar(structured, type);
        Map<String, Object> existing =
                similar.map(m -> parse(m.getStructured())).orElseGet(Map::of);
        Judgement judgement = judges.get(type).judge(structured, existing);

        // targetMemoryId는 judge가 알 수 없어 여기서 후보 id로 채운다(NEW면 대상 없음).
        Memory target = judgement.verdict() == Verdict.NEW ? null : similar.orElse(null);
        ReviewItem item =
                new ReviewItem(
                        capture,
                        type,
                        judgement.verdict(),
                        target,
                        judgement.rationale(),
                        toJson(structured));
        reviewRepository.save(item);
        log.info(
                "검토 대기함 등록: capture={} type={} verdict={} target={}",
                capture.getId(),
                type,
                judgement.verdict(),
                target == null ? null : target.getId());
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

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("structured 파싱 실패", e);
        }
    }
}
