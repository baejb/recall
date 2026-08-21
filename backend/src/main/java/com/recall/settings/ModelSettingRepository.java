package com.recall.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ModelSettingRepository extends JpaRepository<ModelSetting, Long> {

    /** user_id 로 소유자의 model_setting 행을 조회한다. */
    Optional<ModelSetting> findByUserId(long userId);

    /**
     * {@code userId} 소유 model_setting 의 embedding_status 를 행의 현재 embedding_generation 이 {@code
     * generation}과 같을 때만 원자적으로 갱신한다(세대 펜싱, user_id 스코프). 다른 사용자의 행에는 영향을 주지 않는다.
     *
     * @return 갱신된 행 수(0 이면 세대 불일치이거나 해당 user_id 행이 없음 — 호출 측이 건너뜀을 로그로 남긴다)
     */
    @Transactional
    @Modifying
    @Query(
            "UPDATE ModelSetting m SET m.embeddingStatus = :status "
                    + "WHERE m.userId = :userId AND m.embeddingGeneration = :generation")
    int updateEmbeddingStatusIfGeneration(
            @Param("userId") long userId,
            @Param("status") String status,
            @Param("generation") long generation);
}
