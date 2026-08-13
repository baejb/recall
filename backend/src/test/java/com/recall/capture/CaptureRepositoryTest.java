package com.recall.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * capture 상태 저장/조회의 실 DB 계약 검증. markFailed 가 자기 소유 트랜잭션(REQUIRES_NEW)으로 FAILED 를 원자적으로 쓰는지,
 * findByStatusInOrderByCreatedAtDesc 가 대상 상태만 최신순으로 거르는지 본다. 공유 테이블을 쓰므로 만든 행은 정리한다.
 */
@SpringBootTest
class CaptureRepositoryTest {

    @Autowired CaptureRepository captureRepository;

    private final List<Long> created = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        captureRepository.deleteAllById(created);
        created.clear();
    }

    private Capture seed(String status) {
        Capture c = captureRepository.save(new Capture("chat", "마스킹된 원문", "[]"));
        created.add(c.getId());
        if (!"PROCESSING".equals(status)) {
            c.setStatus(status);
            captureRepository.save(c);
        }
        return c;
    }

    @Test
    void markFailedFlipsStatusToFailedWithStage() {
        Capture c = seed("PROCESSING");

        int rows = captureRepository.markFailed(c.getId(), "extract");

        assertEquals(1, rows, "정확히 한 행이 갱신돼야 한다");
        Capture reloaded = captureRepository.findById(c.getId()).orElseThrow();
        assertEquals("FAILED", reloaded.getStatus());
        assertEquals("extract", reloaded.getFailedStage());
    }

    @Test
    void findByStatusInReturnsOnlyMatchingStatusesNewestFirst() {
        Capture processing = seed("PROCESSING");
        Capture failed = seed("FAILED");
        Capture done = seed("DONE");

        List<Long> ids =
                captureRepository
                        .findByStatusInOrderByCreatedAtDesc(List.of("PROCESSING", "FAILED"))
                        .stream()
                        .map(Capture::getId)
                        .toList();

        org.junit.jupiter.api.Assertions.assertTrue(ids.contains(processing.getId()));
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains(failed.getId()));
        org.junit.jupiter.api.Assertions.assertTrue(
                !ids.contains(done.getId()), "DONE 은 포함되지 않아야 한다");
        // 최신순: 나중에 만든 failed 가 먼저 만든 processing 보다 앞에 온다(둘 다 대상 상태).
        int idxProcessing = ids.indexOf(processing.getId());
        int idxFailed = ids.indexOf(failed.getId());
        org.junit.jupiter.api.Assertions.assertTrue(
                idxFailed < idxProcessing, "createdAt 내림차순이어야 한다");
    }

    @Test
    void newCaptureStartsProcessingWithNoFailedStage() {
        Capture c = seed("PROCESSING");
        Capture reloaded = captureRepository.findById(c.getId()).orElseThrow();
        assertEquals("PROCESSING", reloaded.getStatus());
        assertNull(reloaded.getFailedStage());
    }
}
