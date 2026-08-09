package com.recall.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.review.dto.ReviewItemResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 검토 대기함 조회 + 승인/반려. 승인해야만 memory가 된다(불변 원칙: 승인 게이트). */
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(ReviewRepository reviewRepository, MemoryRepository memoryRepository) {
        this.reviewRepository = reviewRepository;
        this.memoryRepository = memoryRepository;
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

    /** 승인 — 제안(proposed)을 memory로 확정하고 검토 항목을 approved로 전이한다. */
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
        item.resolve("approved", OffsetDateTime.now());
        return memoryId;
    }

    /** 반려 — memory를 만들지 않고 검토 항목만 rejected로 전이한다(삭제 아님, 상태 보존). */
    @Transactional
    public void reject(Long reviewId) {
        findPending(reviewId).resolve("rejected", OffsetDateTime.now());
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
                item.getStatus(),
                item.getProposed(),
                item.getCreatedAt());
    }
}
