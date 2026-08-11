package com.recall.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.MemoryType;
import com.recall.common.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
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
    private final EmbeddingClient embeddingClient;
    private final StrategyRegistry<SearchRepresentation> searchReps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(
            ReviewRepository reviewRepository,
            MemoryRepository memoryRepository,
            MemorySearchStore searchStore,
            EmbeddingClient embeddingClient,
            List<SearchRepresentation> searchRepresentations) {
        this.reviewRepository = reviewRepository;
        this.memoryRepository = memoryRepository;
        this.searchStore = searchStore;
        this.embeddingClient = embeddingClient;
        this.searchReps = new StrategyRegistry<>(searchRepresentations);
    }

    /** 승인 대기(pending) 목록을 오래된 순으로. */
    @Transactional(readOnly = true)
    public List<ReviewItemResponse> listPending() {
        return reviewRepository.findByStatusOrderByCreatedAtAsc("pending").stream()
                .map(ReviewService::toResponse)
                .toList();
    }

    /** 승인 대기 건수. */
    @Transactional(readOnly = true)
    public long countPending() {
        return reviewRepository.countByStatus("pending");
    }

    /** 승인 — 제안(proposed)을 memory로 확정하고 검색 인덱스를 채운 뒤 검토 항목을 approved로 전이한다. */
    @Transactional
    public Long approve(Long reviewId) {
        ReviewItem item = findPending(reviewId);
        Memory memory =
                new Memory(
                        item.getCapture(),
                        item.getType(),
                        readTitle(item.getProposed()),
                        item.getProposed());
        Long memoryId = memoryRepository.save(memory).getId();
        indexForSearch(memoryId, item.getType(), item.getProposed());
        item.resolve("approved", OffsetDateTime.now());
        return memoryId;
    }

    /** 반려 — memory를 만들지 않고 검토 항목만 rejected로 전이한다(삭제 아님, 상태 보존). */
    @Transactional
    public void reject(Long reviewId) {
        findPending(reviewId).resolve("rejected", OffsetDateTime.now());
    }

    /**
     * 승인된 memory를 검색 대상으로 인덱싱한다: BM25용 tsvector + 유형별 검색 표현의 kind별 임베딩. 인덱싱 실패는 memory를 유지한 채 로그로
     * 드러낸다(부분성공 노출 — 조용한 실패 금지). 임베딩이 stub(0벡터)이면 벡터 검색은 무의미하다.
     */
    private void indexForSearch(Long memoryId, MemoryType type, String proposedJson) {
        try {
            Map<String, Object> structured = parseStructured(proposedJson);
            searchStore.updateSearchTsv(memoryId, keywordText(structured));
            Map<String, String> texts = searchReps.get(type).embeddingTexts(structured);
            texts.forEach(
                    (kind, text) ->
                            searchStore.saveEmbedding(
                                    memoryId, kind, embeddingClient.embedDocument(text)));
        } catch (RuntimeException e) {
            log.warn("검색 인덱싱 실패(memory는 유지) memoryId={}: {}", memoryId, e.getMessage());
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
                        .findById(reviewId)
                        .orElseThrow(() -> new IllegalArgumentException("검토 항목 없음: " + reviewId));
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
