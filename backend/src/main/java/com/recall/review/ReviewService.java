package com.recall.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.CurrentUserProvider;
import com.recall.common.MemoryType;
import com.recall.common.NotFoundException;
import com.recall.common.SecretMasking;
import com.recall.common.StrategyRegistry;
import com.recall.llm.AiContextFactory;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.type.SearchRepresentation;
import com.recall.review.dto.ReviewItemResponse;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(
            ReviewRepository reviewRepository,
            MemoryRepository memoryRepository,
            MemorySearchStore searchStore,
            AiContextFactory contextFactory,
            CurrentUserProvider currentUser,
            List<SearchRepresentation> searchRepresentations) {
        this.reviewRepository = reviewRepository;
        this.memoryRepository = memoryRepository;
        this.searchStore = searchStore;
        this.contextFactory = contextFactory;
        this.currentUser = currentUser;
        this.searchReps = new StrategyRegistry<>(searchRepresentations);
    }

    /** 승인 대기(pending) 목록을 오래된 순으로. */
    @Transactional(readOnly = true)
    public List<ReviewItemResponse> listPending() {
        return reviewRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(currentUser.currentUserId(), "pending")
                .stream()
                .map(ReviewService::toResponse)
                .toList();
    }

    /** 승인 대기 건수. */
    @Transactional(readOnly = true)
    public long countPending() {
        return reviewRepository.countByUserIdAndStatus(currentUser.currentUserId(), "pending");
    }

    /**
     * 승인 — 제안(proposed)을 memory로 확정하고 검색 인덱스를 채운 뒤 검토 항목을 approved로 전이한다. 인덱싱은 소유자(현재 사용자, 이미
     * {@code findPending}에서 소유권 검증됨)의 임베딩 컨텍스트로 수행한다 — 소유자가 embedding을 설정하지 않았으면 {@link
     * com.recall.common.AiNotConfiguredException}이 전파돼 이 트랜잭션 전체가 롤백된다(memory 미저장·검토 항목 미확정 — 부분 상태
     * 없는 깨끗한 409).
     */
    @Transactional
    public Long approve(Long reviewId) {
        ReviewItem item = findPending(reviewId);
        UserAiContext ctx = contextFactory.forUser(currentUser.currentUserId());
        Memory memory =
                new Memory(
                        item.getCapture(),
                        item.getType(),
                        readTitle(item.getProposed()),
                        item.getProposed());
        Long memoryId = memoryRepository.save(memory).getId();
        indexForSearch(memoryId, item.getType(), item.getProposed(), ctx);
        item.resolve("approved", OffsetDateTime.now());
        return memoryId;
    }

    /** 반려 — memory를 만들지 않고 검토 항목만 rejected로 전이한다(삭제 아님, 상태 보존). */
    @Transactional
    public void reject(Long reviewId) {
        findPending(reviewId).resolve("rejected", OffsetDateTime.now());
    }

    /**
     * 승인된 memory를 검색 대상으로 인덱싱한다: BM25용 tsvector + 유형별 검색 표현의 kind별 임베딩(소유자 {@code ctx}에 바인딩된
     * embedding 클라이언트로). embedding이 미설정이면 {@code ctx.requireEmbedding()}이 즉시 {@link
     * com.recall.common.AiNotConfiguredException}을 던져 승인 자체를 막는다(아래 try/catch 밖 — 여기는 삼키지 않는다). 그
     * 이후, 실제 인덱싱 쓰기·외부 임베딩 호출의 실패(예: provider 일시 장애)는 memory를 유지한 채 로그로만 드러낸다(부분성공 노출 — 조용한 실패 금지).
     */
    private void indexForSearch(
            Long memoryId, MemoryType type, String proposedJson, UserAiContext ctx) {
        EmbeddingClient embedding = ctx.requireEmbedding();
        try {
            Map<String, Object> structured = parseStructured(proposedJson);
            searchStore.updateSearchTsv(memoryId, keywordText(structured));
            Map<String, String> texts = searchReps.get(type).embeddingTexts(structured);
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

    /** BM25 대상 텍스트 — 제목·요약·키워드를 합친다. */
    private String keywordText(Map<String, Object> structured) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, structured.get("title"));
        appendIfPresent(sb, structured.get("summary"));
        Object keywords = structured.get("keywords");
        if (keywords instanceof List<?> list) {
            list.forEach(k -> appendIfPresent(sb, k));
        } else {
            appendIfPresent(sb, keywords);
        }
        return sb.toString().strip();
    }

    private void appendIfPresent(StringBuilder sb, Object value) {
        if (value != null && !value.toString().isBlank()) {
            sb.append(value).append(' ');
        }
    }

    private Map<String, Object> parseStructured(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("proposed 파싱 실패", e);
        }
    }

    private ReviewItem findPending(Long reviewId) {
        ReviewItem item =
                reviewRepository
                        .findByIdAndUserId(reviewId, currentUser.currentUserId())
                        // 없거나 남의 것이면 404 — 존재를 노출하지 않는다(by-id 접근 격리, CLAUDE.md).
                        .orElseThrow(() -> new NotFoundException("검토 항목 없음: " + reviewId));
        if (!"pending".equals(item.getStatus())) {
            throw new IllegalStateException(
                    "이미 처리된 검토 항목: " + reviewId + " (" + item.getStatus() + ")");
        }
        return item;
    }

    private String readTitle(String proposedJson) {
        try {
            JsonNode node = objectMapper.readTree(proposedJson).get("title");
            return node == null ? "(제목 없음)" : node.asText();
        } catch (Exception e) {
            throw new IllegalStateException("proposed 파싱 실패", e);
        }
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
