package com.recall.review.service;

import com.recall.common.config.CurrentUserProvider;
import com.recall.common.exception.ConflictException;
import com.recall.common.exception.NotFoundException;
import com.recall.common.secret.SecretMasking;
import com.recall.common.type.MemoryType;
import com.recall.common.type.StrategyRegistry;
import com.recall.llm.AiContextFactory;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.repository.MemoryRepository;
import com.recall.memory.repository.MemorySearchStore;
import com.recall.memory.service.entity.Memory;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SearchRepresentation;
import com.recall.review.controller.dto.ReviewItemResponse;
import com.recall.review.repository.ReviewRepository;
import com.recall.review.service.entity.ReviewItem;
import com.recall.review.service.entity.ReviewStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 검토 대기함 조회 + 승인/반려. 승인해야만 memory가 된다(불변 원칙: 승인 게이트). */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final MemoryRepository memoryRepository;
    private final MemorySearchStore searchStore;
    private final AiContextFactory contextFactory;
    private final CurrentUserProvider currentUser;
    private final StrategyRegistry<SearchRepresentation> searchReps;

    /** 카드 ↔ JSON 변환은 이 코덱만 한다(모듈마다 ObjectMapper 를 두면 되읽기가 유형 스키마를 건너뛴다). */
    private final CardCodec cardCodec;

    public ReviewService(
            ReviewRepository reviewRepository,
            MemoryRepository memoryRepository,
            MemorySearchStore searchStore,
            AiContextFactory contextFactory,
            CurrentUserProvider currentUser,
            CardCodec cardCodec,
            List<SearchRepresentation> searchRepresentations) {
        this.reviewRepository = reviewRepository;
        this.memoryRepository = memoryRepository;
        this.searchStore = searchStore;
        this.contextFactory = contextFactory;
        this.currentUser = currentUser;
        this.cardCodec = cardCodec;
        this.searchReps = new StrategyRegistry<>(searchRepresentations);
    }

    /** 승인 대기(pending) 목록을 오래된 순으로. */
    @Transactional(readOnly = true)
    public List<ReviewItemResponse> listPending() {
        return reviewRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(
                        currentUser.currentUserId(), ReviewStatus.PENDING)
                .stream()
                .map(ReviewService::toResponse)
                .toList();
    }

    /** 승인 대기 건수. */
    @Transactional(readOnly = true)
    public long countPending() {
        return reviewRepository.countByUserIdAndStatus(
                currentUser.currentUserId(), ReviewStatus.PENDING);
    }

    /**
     * 승인 — 제안(proposed)을 memory로 확정하고 검색 인덱스를 채운 뒤 검토 항목을 approved로 전이한다. 인덱싱은 소유자(현재 사용자, 이미
     * {@code findPending}에서 소유권 검증됨)의 임베딩 컨텍스트로 수행한다 — 소유자가 embedding을 설정하지 않았으면 {@link
     * com.recall.common.exception.AiNotConfiguredException}이 전파돼 이 트랜잭션 전체가 롤백된다(memory 미저장·검토 항목
     * 미확정 — 부분 상태 없는 깨끗한 409).
     */
    @Transactional
    public Long approve(Long reviewId) {
        ReviewItem item = findPending(reviewId);
        UserAiContext ctx = contextFactory.forUser(currentUser.currentUserId());
        // proposed JSON 을 유형 카드로 되읽는다 — 필드 이름을 이 모듈이 문자열로 다시 적지 않고,
        // 되읽는 시점에 카드 생성자의 정규화도 함께 거친다.
        //
        // 여기서는 격하하지 않는다: 카드가 곧 승인 대상이라 건너뛸 것이 없다. 다만 500 으로 흘리지 않고
        // 409 로 분류해 사용자가 다음 행동(반려 후 재저장)을 알 수 있게 한다 — 조회·재색인 경로는
        // readOrNull 로 그 한 건만 건너뛰지만, 이 경로는 그럴 수 없다.
        MemoryCard card = cardCodec.readOrNull(item.getType(), item.getProposed());
        if (card == null) {
            log.warn("검토 항목의 카드를 읽을 수 없어 승인을 거절한다 reviewId={}", reviewId);
            throw new ConflictException(
                    "이 검토 항목의 카드를 읽을 수 없어 승인할 수 없습니다 — 반려하고 다시 저장해 주세요: " + reviewId);
        }
        Memory memory =
                new Memory(item.getCapture(), item.getType(), title(card), item.getProposed());
        Long memoryId = memoryRepository.save(memory).getId();
        indexForSearch(memoryId, item.getType(), card, ctx);
        item.resolve(ReviewStatus.APPROVED, OffsetDateTime.now());
        return memoryId;
    }

    /** 반려 — memory를 만들지 않고 검토 항목만 rejected로 전이한다(삭제 아님, 상태 보존). */
    @Transactional
    public void reject(Long reviewId) {
        findPending(reviewId).resolve(ReviewStatus.REJECTED, OffsetDateTime.now());
    }

    /**
     * 승인된 memory를 검색 대상으로 인덱싱한다: BM25용 tsvector + 유형별 검색 표현의 kind별 임베딩(소유자 {@code ctx}에 바인딩된
     * embedding 클라이언트로). embedding이 미설정이면 {@code ctx.requireEmbedding()}이 즉시 {@link
     * com.recall.common.exception.AiNotConfiguredException}을 던져 승인 자체를 막는다(아래 try/catch 밖 — 여기는 삼키지
     * 않는다). 그 이후, 실제 인덱싱 쓰기·외부 임베딩 호출의 실패(예: provider 일시 장애)는 memory를 유지한 채 로그로만 드러낸다(부분성공 노출 — 조용한
     * 실패 금지).
     */
    private void indexForSearch(
            Long memoryId, MemoryType type, MemoryCard card, UserAiContext ctx) {
        EmbeddingClient embedding = ctx.requireEmbedding();
        try {
            searchStore.updateSearchTsv(memoryId, keywordText(card));
            Map<String, String> texts = searchReps.get(type).embeddingTexts(card);
            texts.forEach(
                    (kind, text) ->
                            searchStore.saveEmbedding(
                                    memoryId, kind, embedding.embedDocument(text)));
        } catch (RuntimeException e) {
            log.warn(
                    "검색 인덱싱 실패(memory는 유지) memoryId={}: {}",
                    memoryId,
                    SecretMasking.mask(e.getMessage()));
        }
    }

    /**
     * BM25 대상 텍스트 — 제목·요약·키워드를 합친다.
     *
     * <p>카드 접근자로 읽는다 — 전엔 {@code structured.get("title")} 처럼 필드 이름을 이 모듈이 문자열로 다시 적었고, 값 타입도 안 지켜져
     * {@code keywords instanceof List<?>} 방어를 여기서 또 써야 했다(카드 record 가 이미 정규화한 값인데도).
     */
    private String keywordText(MemoryCard card) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, card.title());
        appendIfPresent(sb, card.summary());
        card.keywords().forEach(keyword -> appendIfPresent(sb, keyword));
        return sb.toString().strip();
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value).append(' ');
        }
    }

    private ReviewItem findPending(Long reviewId) {
        ReviewItem item =
                reviewRepository
                        .findByIdAndUserId(reviewId, currentUser.currentUserId())
                        // 없거나 남의 것이면 404 — 존재를 노출하지 않는다(by-id 접근 격리, CLAUDE.md).
                        .orElseThrow(() -> new NotFoundException("검토 항목 없음: " + reviewId));
        if (!ReviewStatus.PENDING.equals(item.getStatus())) {
            // 409 — 호출자가 고칠 수 있는 상황이다(이미 처리됨). 전에는 IllegalStateException 이라
            // 전역 핸들러의 catch-all 이 붙잡아 500 으로 나갔다: 서버 장애로 잘못 보고됐다.
            throw new ConflictException(
                    "이미 처리된 검토 항목: " + reviewId + " (" + item.getStatus() + ")");
        }
        return item;
    }

    /**
     * {@code memory.title}(NOT NULL)로 쓸 제목. 카드 스키마가 null 을 빈 문자열로 정규화하므로 여기선 "비었을 때의 표시값"만 정한다 —
     * 전에는 이 모듈이 {@code readTree(...).get("title")}로 JSON 을 직접 뒤져 기본값을 붙였다.
     */
    private static String title(MemoryCard card) {
        return card.title().isBlank() ? "(제목 없음)" : card.title();
    }

    private static ReviewItemResponse toResponse(ReviewItem item) {
        return new ReviewItemResponse(
                item.getId(),
                item.getCapture().getId(),
                item.getJudgement().name(),
                item.getMemory() == null ? null : item.getMemory().getId(),
                item.getJudgeReason(),
                item.getType() == null ? null : item.getType().name(),
                item.getStatus(),
                item.getProposed(),
                item.getCreatedAt());
    }
}
