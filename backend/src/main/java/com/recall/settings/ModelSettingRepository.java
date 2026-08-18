package com.recall.settings;

import java.util.Optional;
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
     * @deprecated 전역 id=1 스코프 — user_id 스코프 오버로드로 대체 예정(Task 9에서 호출부 이전 후 제거).
     * @return 갱신된 행 수(0 이면 이미 더 새로운 세대로 대체된 것 — 호출 측이 건너뜀을 로그로 남긴다)
     */
    @Deprecated
    @Transactional
    @Modifying
    @Query(
            "UPDATE ModelSetting m SET m.embeddingStatus = :status "
                    + "WHERE m.id = 1 AND m.embeddingGeneration = :generation")
    int updateEmbeddingStatusIfGeneration(
            @Param("status") String status, @Param("generation") long generation);

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
