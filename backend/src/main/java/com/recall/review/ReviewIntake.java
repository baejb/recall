package com.recall.review;

import com.recall.capture.service.entity.Capture;
import com.recall.common.type.MemoryType;
import com.recall.memory.service.entity.Memory;
import com.recall.memory.type.Verdict;
import com.recall.review.repository.ReviewRepository;
import com.recall.review.service.entity.ReviewItem;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토 대기함의 <b>입구</b> — 다른 모듈이 항목을 올리는 유일한 경로.
 *
 * <p><b>왜 생겼나</b> — 저장 파이프라인(store)이 {@code ReviewItem} 을 직접 만들어 {@code ReviewRepository.save} 로
 * 넣었다. 그러면 불변 원칙 1(<b>승인 게이트</b>)의 입구를 store 가 소유하게 된다: review 모듈이 "대기함에 올릴 때 이것을 검사한다"는 규칙을 나중에
 * 추가해도 store 경로는 그 규칙을 우회한다. 게이트를 지키는 코드와 게이트에 넣는 코드가 다른 모듈에 있으면, 게이트는 규칙이 아니라 관습이다.
 *
 * <p>인자를 id 로 받는 이유: 엔티티를 주고받으면 store 가 memory·capture 의 엔티티를 손에 들어야 하고, 그게 원래 문제였다. FK 참조 해석은
 * <b>연관을 소유한 이 모듈</b>이 한다.
 */
@Service
public class ReviewIntake {

    private final ReviewRepository reviewRepository;
    private final EntityManager entityManager;

    public ReviewIntake(ReviewRepository reviewRepository, EntityManager entityManager) {
        this.reviewRepository = reviewRepository;
        this.entityManager = entityManager;
    }

    /**
     * 판정 결과를 검토 대기함에 올린다.
     *
     * <p><b>새 트랜잭션을 열지 않는다</b>(REQUIRED). 호출자는 이 저장과 capture 의 완료 전이를 한 트랜잭션으로 커밋해야 한다 — 대기함에는 올랐는데
     * capture 가 처리중으로 남으면 재처리 판단 근거가 사라진다.
     *
     * @param targetMemoryId 대조된 기존 기억(신규 판정이면 {@code null})
     */
    @Transactional
    public void enqueue(
            long captureId,
            MemoryType type,
            Verdict verdict,
            Long targetMemoryId,
            String rationale,
            String proposedJson) {
        Capture capture = entityManager.getReference(Capture.class, captureId);
        Memory target =
                targetMemoryId == null
                        ? null
                        : entityManager.getReference(Memory.class, targetMemoryId);
        reviewRepository.save(
                new ReviewItem(capture, type, verdict, target, rationale, proposedJson));
    }
}
