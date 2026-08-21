package com.recall.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ModelSettingRepository extends JpaRepository<ModelSetting, Long> {

    /**
     * model_setting(id=1) 의 embedding_status 를 행의 현재 embedding_generation 이 {@code generation}과 같을
     * 때만 원자적으로 갱신한다(세대 펜싱). embedding_generation 컬럼 자체는 건드리지 않으므로, 전체 엔티티를 읽고 다시 저장하는 방식과 달리 다른
     * 트랜잭션이 그 사이 세대를 올려도 그 값을 덮어쓸 수 없다.
     *
     * @return 갱신된 행 수(0 이면 이미 더 새로운 세대로 대체된 것 — 호출 측이 건너뜀을 로그로 남긴다)
     */
    @Transactional
    @Modifying
    @Query(
            "UPDATE ModelSetting m SET m.embeddingStatus = :status "
                    + "WHERE m.id = 1 AND m.embeddingGeneration = :generation")
    int updateEmbeddingStatusIfGeneration(
            @Param("status") String status, @Param("generation") long generation);
}
