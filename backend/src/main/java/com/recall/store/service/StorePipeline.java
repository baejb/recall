package com.recall.store.service;

import com.recall.capture.CaptureCreatedEvent;
import com.recall.capture.repository.CaptureRepository;
import com.recall.capture.service.entity.Capture;
import com.recall.capture.service.entity.CaptureStatus;
import com.recall.common.exception.AiNotConfiguredException;
import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.AiContextFactory;
import com.recall.llm.UserAiContext;
import com.recall.memory.service.entity.Memory;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.Judgement;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SimilarityJudgeStrategy;
import com.recall.memory.type.Verdict;
import com.recall.review.repository.ReviewRepository;
import com.recall.review.service.entity.ReviewItem;
import java.util.List;
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
    private final LongContextExtractor longContextExtractor;
    private final TypeClassifier typeClassifier;
    private final AiContextFactory contextFactory;
    private final StrategyRegistry<SimilarityJudgeStrategy> judges;

    /** 카드 ↔ JSON 변환은 이 코덱만 한다 — 클래스마다 ObjectMapper 를 두면 되읽기가 유형 스키마를 건너뛴다. */
    private final CardCodec cardCodec;

    public StorePipeline(
            CaptureRepository captureRepository,
            ReviewRepository reviewRepository,
            SimilarMemoryFinder similarMemoryFinder,
            LongContextExtractor longContextExtractor,
            TypeClassifier typeClassifier,
            AiContextFactory contextFactory,
            CardCodec cardCodec,
            List<SimilarityJudgeStrategy> judgeStrategies) {
        this.captureRepository = captureRepository;
        this.reviewRepository = reviewRepository;
        this.similarMemoryFinder = similarMemoryFinder;
        this.longContextExtractor = longContextExtractor;
        this.typeClassifier = typeClassifier;
        this.contextFactory = contextFactory;
        this.cardCodec = cardCodec;
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
        // 진행 단계를 추적해 실패 시 어느 단계에서 죽었는지 상태로 드러낸다(조용한 실패 금지).
        // 단계 순서: context → classify → extract → judge → review.
        String stage = "context";
        try {
            Capture capture =
                    captureRepository
                            .findById(event.captureId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "capture 없음: " + event.captureId()));

            // 소유자(capture.user_id, DB 기준)로 AI 컨텍스트를 해석한다 — 비동기 스레드엔 요청 스레드의
            // CurrentUserProvider/thread-local 이 없으므로 절대 그걸 신뢰하지 않는다(다른 사용자의 요청이 동시에
            // 떠 있어도 소유자 정확성이 지켜진다). 저장 경로는 추출(chat)과 색인/판정(embedding) 둘 다 필요하다 —
            // 둘 중 하나라도 미설정이면 여기서 막아 이후 단계로 새지 않게 한다.
            UserAiContext ctx = contextFactory.forUser(capture.getUserId());
            if (!ctx.chatReady() || !ctx.embeddingReady()) {
                throw new AiNotConfiguredException("소유자 AI 미설정(user=" + capture.getUserId() + ")");
            }

            stage = "classify";
            // 유형 라우팅(🔵) — 등록된 추출 전략의 유형 중에서만 고르고, 실패·모르는 출력은 기본 유형으로 격하한다.
            // 유형을 잘못 골라도 원문은 capture에 남고, 잘못된 카드는 검토에서 반려할 수 있다(승인 게이트).
            MemoryType type = typeClassifier.classify(event.maskedText(), ctx);

            stage = "extract";
            // S2/S3 — 짧으면 단일 패스, 길면 긴맥락 Map-Reduce(청킹→조각추출→병합). LLM 은 ctx 에 바인딩된
            // 것만 쓴다(전역 싱글턴 아님) — 사용자별 provider/키 교차유출 방지.
            MemoryCard card = longContextExtractor.extract(type, event.maskedText(), ctx);

            stage = "judge";
            // S4 — 유사 기존 기억을 찾아 대조 판정. 같은 사용자(capture 소유자)의 기억끼리만 대조한다.
            // 후보가 없으면 빈 카드가 아니라 null 을 넘긴다(전략이 "후보 없음"을 구분할 수 있게).
            Optional<Memory> similar =
                    similarMemoryFinder.findSimilar(capture.getUserId(), card, type, ctx);
            // 기존 후보의 카드를 못 읽으면(정규화를 거치지 않은 레거시 행 등) "후보 없음"으로 격하한다 —
            // 그 한 건 때문에 이 capture 가 stage=judge FAILED 로 죽으면, 그 후보에 유사 판정되는
            // **모든 신규 capture** 가 함께 막힌다(결함 하나가 저장 경로를 계속 갉아먹는다).
            MemoryCard existing =
                    similar.map(m -> cardCodec.readOrNull(type, m.getStructured())).orElse(null);
            if (similar.isPresent() && existing == null) {
                log.warn("유사 후보 카드를 읽을 수 없어 대조 없이 판정한다 memoryId={}", similar.get().getId());
            }
            Judgement judgement = judges.get(type).judge(card, existing, ctx);

            stage = "review";
            // targetMemoryId는 judge가 알 수 없어 여기서 후보 id로 채운다(NEW면 대상 없음).
            Memory target = judgement.verdict() == Verdict.NEW ? null : similar.orElse(null);
            ReviewItem item =
                    new ReviewItem(
                            capture,
                            type,
                            judgement.verdict(),
                            target,
                            judgement.rationale(),
                            cardCodec.writeJson(card));
            reviewRepository.save(item);

            // 성공: 검토 항목과 함께 DONE 을 이 REQUIRES_NEW 트랜잭션으로 같이 커밋한다(관리 엔티티 dirty flush).
            capture.setStatus(CaptureStatus.DONE);
            log.info(
                    "검토 대기함 등록: capture={} type={} verdict={} target={}",
                    capture.getId(),
                    type,
                    judgement.verdict(),
                    target == null ? null : target.getId());
        } catch (AiNotConfiguredException e) {
            // 소유자 AI 미설정은 classify/extract/judge 어떤 단계에도 귀속시키지 않는다 — 항상 context 로
            // 드러낸다(원인 혼동 방지). markFailed 는 자기 소유의 REQUIRES_NEW 트랜잭션으로 독립 커밋한다.
            log.error("캡처 처리 실패 stage=context capture={}", event.captureId(), e);
            captureRepository.markFailed(event.captureId(), "context");
        } catch (Exception e) {
            // 조용한 실패 금지: 실패 단계를 로그로 드러내고 FAILED 를 durable 하게 남긴다.
            log.error("캡처 처리 실패 stage={} capture={}", stage, event.captureId(), e);
            // markFailed 는 자기 소유의 REQUIRES_NEW 트랜잭션이라, 이 (예외로 무의미해진) 트랜잭션과 무관하게
            // FAILED 를 독립 커밋한다. 검토 항목은 성공 경로에서만 save 하므로 부분 항목이 새지 않는다.
            captureRepository.markFailed(event.captureId(), stage);
            // 이미 FAILED 로 durable 하게 드러났으므로 재던지지 않는다.
        }
    }
}
