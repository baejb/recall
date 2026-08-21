package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ModelSettingRepositoryTest {

    @Autowired ModelSettingRepository repository;

    @Test
    void seedRowExists() {
        ModelSetting s = repository.findById(1L).orElseThrow();
        assertEquals("anthropic", s.getChatProvider());
        assertEquals("READY", s.getEmbeddingStatus());
    }

    @Test
    @DisplayName("findByUserId로 부트스트랩(1) 설정 조회")
    void findsByUserId() {
        assertTrue(repository.findByUserId(1L).isPresent());
    }

    @Test
    @DisplayName("세대 펜싱은 user_id로 스코프 — 다른 사용자 세대는 안 건드림")
    void generationFencingScopedByUser() {
        // 부트스트랩(1) 현재 세대로만 갱신되고, 존재하지 않는 user 2 조건은 0행.
        long gen = repository.findByUserId(1L).orElseThrow().getEmbeddingGeneration();
        assertEquals(1, repository.updateEmbeddingStatusIfGeneration(1L, "READY", gen));
        assertEquals(0, repository.updateEmbeddingStatusIfGeneration(2L, "READY", gen));
    }

    /**
     * 실 DB에 대고 세대 펜싱 UPDATE 가 정말 원자적 조건부 문장인지 검증한다(mock 은 SQL 자체를 증명 못 함). 다른 테스트와 싱글턴 행(id=1)을
     * 공유하므로 원래 상태·세대를 스냅샷/복원한다({@code SettingsFlowSmokeTest}와 동일한 패턴).
     */
    @Nested
    class UpdateEmbeddingStatusIfGeneration {

        private String originalStatus;
        private long originalGeneration;

        @BeforeEach
        void snapshot() {
            ModelSetting s = repository.findById(1L).orElseThrow();
            originalStatus = s.getEmbeddingStatus();
            originalGeneration = s.getEmbeddingGeneration();
            s.setEmbeddingGeneration(2L);
            s.setEmbeddingStatus("REINDEXING");
            repository.save(s);
        }

        @AfterEach
        void restore() {
            ModelSetting s = repository.findById(1L).orElseThrow();
            s.setEmbeddingStatus(originalStatus);
            s.setEmbeddingGeneration(originalGeneration);
            repository.save(s);
        }

        @Test
        void staleGenerationUpdatesNoRowsAndLeavesStatusUnchanged() {
            int updated = repository.updateEmbeddingStatusIfGeneration(1L, "READY", 1L);

            assertEquals(0, updated);
            assertEquals("REINDEXING", repository.findById(1L).orElseThrow().getEmbeddingStatus());
        }

        @Test
        void currentGenerationUpdatesStatusButNeverTouchesGeneration() {
            int updated = repository.updateEmbeddingStatusIfGeneration(1L, "READY", 2L);

            assertEquals(1, updated);
            ModelSetting after = repository.findById(1L).orElseThrow();
            assertEquals("READY", after.getEmbeddingStatus());
            assertEquals(2L, after.getEmbeddingGeneration(), "embedding_generation 은 절대 덮어써지지 않는다");
        }
    }
}
