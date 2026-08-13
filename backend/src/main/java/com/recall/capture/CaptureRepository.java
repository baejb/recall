package com.recall.capture;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** capture 저장/조회 창구. JpaRepository 상속만으로 save/findById/findAll 등이 자동 제공된다. */
public interface CaptureRepository extends JpaRepository<Capture, Long> {

    /** 처리 중/실패한(아직 검토 대기함에 오르지 않은) 캡처를 최신순으로 — 상태 노출 엔드포인트용. */
    List<Capture> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

    /**
     * 실패 상태를 원자적으로, 그리고 자기 소유의 새 트랜잭션에 기록한다(REQUIRES_NEW). 파이프라인 트랜잭션이 롤백/무의미하게 커밋돼도 FAILED 는 독립적으로
     * 커밋돼 남는다(조용한 실패 금지). JPQL 벌크 UPDATE 라 영속성 컨텍스트를 우회한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("UPDATE Capture c SET c.status = 'FAILED', c.failedStage = :stage WHERE c.id = :id")
    int markFailed(@Param("id") Long id, @Param("stage") String stage);
}
