package com.recall.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.capture.Capture;
import com.recall.capture.CaptureRepository;
import com.recall.common.MemoryType;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.type.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 🔴 릴리스 차단 게이트 — <b>충돌 자동 덮어쓰기 금지</b>(불변 원칙 #3). 모순되는 후보(CONFLICT)를 승인해도 기존 기억을 덮어쓰거나 지우지 않고, 두
 * 기록을 모두 보존한다. 이 게이트가 깨지면 병합을 막는다.
 */
@SpringBootTest
@Tag("release-gate")
class ConflictOverwriteGateTest {

    @Autowired ReviewService reviewService;
    @Autowired MemoryRepository memoryRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired CaptureRepository captureRepository;

    private final List<Long> memIds = new ArrayList<>();
    private final List<Long> reviewIds = new ArrayList<>();
    private Long captureId;

    @AfterEach
    void cleanup() {
        reviewRepository.deleteAllById(reviewIds); // memory FK 참조가 있어 먼저 지운다
        memoryRepository.deleteAllById(memIds);
        if (captureId != null) {
            captureRepository.deleteById(captureId);
        }
        reviewIds.clear();
        memIds.clear();
        captureId = null;
    }

    @Test
    @DisplayName("🔴 충돌(CONFLICT) 승인은 기존 기억을 덮어쓰지 않는다 — 두 기록 보존")
    void conflictApprovalDoesNotOverwriteExisting() {
        Capture c = captureRepository.save(new Capture("chat", "마스킹된 원문", "[]"));
        captureId = c.getId();

        // 기존 기억(포트=8080)
        Memory existing =
                new Memory(
                        c,
                        MemoryType.KNOWLEDGE,
                        "기존 사실: 포트는 8080",
                        "{\"title\":\"기존 사실\",\"facts\":[\"포트는 8080\"]}");
        memoryRepository.saveAndFlush(existing);
        memIds.add(existing.getId());
        String existingTitle = existing.getTitle();

        // 모순 후보(포트=9090)를 기존 기억 대상으로 CONFLICT 판정된 검토 항목
        ReviewItem item =
                new ReviewItem(
                        c,
                        MemoryType.KNOWLEDGE,
                        Verdict.CONFLICT,
                        existing,
                        "포트 값이 기존과 충돌",
                        "{\"title\":\"새 사실\",\"facts\":[\"포트는 9090\"]}");
        reviewRepository.saveAndFlush(item);
        reviewIds.add(item.getId());

        Long newId = reviewService.approve(item.getId());
        memIds.add(newId);

        // 기존 기억은 자동 덮어쓰기·폐기 없이 그대로 보존된다.
        // (structured는 JSONB라 저장 시 키 순서·공백이 정규화되므로 문자열 완전일치 대신 내용 불변식으로 검증)
        Memory after = memoryRepository.findById(existing.getId()).orElseThrow();
        assertEquals("active", after.getStatus(), "기존 기억이 여전히 active(자동 상태전이 없음)");
        assertEquals(existingTitle, after.getTitle(), "기존 제목 불변");
        assertTrue(after.getStructured().contains("포트는 8080"), "기존 사실 그대로 보존");
        assertFalse(after.getStructured().contains("포트는 9090"), "충돌 값으로 덮어쓰지 않음");

        // 새 후보는 별도 기억으로 생성돼 두 기록이 공존한다(사람이 검토로 정리).
        assertNotEquals(existing.getId(), newId, "새 기억이 별도로 생성됨");
        assertTrue(memoryRepository.findById(newId).isPresent(), "새 기억 존재");
    }
}
