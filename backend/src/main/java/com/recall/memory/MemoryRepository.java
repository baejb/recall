package com.recall.memory;

import com.recall.common.MemoryType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** memory 저장/조회 창구. */
public interface MemoryRepository extends JpaRepository<Memory, Long> {

    /** 유형별 활성 카드 조회(메서드 이름만으로 쿼리 자동 생성). */
    List<Memory> findByTypeAndStatus(MemoryType type, String status);

    /** 상태별 카드를 최신순으로(목록 조회). */
    List<Memory> findByStatusOrderByCreatedAtDesc(String status);
}
