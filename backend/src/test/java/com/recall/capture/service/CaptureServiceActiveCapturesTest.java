package com.recall.capture.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.capture.repository.CaptureRepository;
import com.recall.capture.service.entity.Capture;
import com.recall.common.config.CurrentUserProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 상태 스트립용 처리중/실패 캡처 조회가 무제한 select 를 하지 않는지(PR#4 리뷰 코멘트). 오래된 FAILED 가 누적돼도 상한(Limit)이 걸려야 메모리·응답이
 * 폭발하지 않는다.
 */
class CaptureServiceActiveCapturesTest {

    private static final long USER = 7L;

    private CaptureService service(CaptureRepository repo) {
        CurrentUserProvider currentUser = () -> USER;
        return new CaptureService(
                repo,
                mock(MaskingService.class),
                mock(ApplicationEventPublisher.class),
                currentUser,
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("activeCaptures 는 상한(Limit=200)을 걸어 조회한다 — 무제한 select 금지")
    void queriesWithLimit() {
        CaptureRepository repo = mock(CaptureRepository.class);
        when(repo.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER), any(), any()))
                .thenReturn(List.of());

        service(repo).activeCaptures();

        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(repo).findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER), any(), limit.capture());
        assertEquals(200, limit.getValue().max(), "무제한이 아니라 상한이 걸려야 한다");
    }

    @Test
    @DisplayName("조회 결과를 상태 응답으로 매핑한다(원문 미포함)")
    void mapsRowsToStatusResponses() {
        CaptureRepository repo = mock(CaptureRepository.class);
        Capture row = mock(Capture.class);
        when(row.getId()).thenReturn(11L);
        when(row.getStatus()).thenReturn("FAILED");
        when(row.getSourceType()).thenReturn("chat");
        when(row.getFailedStage()).thenReturn("S2");
        when(repo.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER), any(), any()))
                .thenReturn(List.of(row));

        var result = service(repo).activeCaptures();

        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).id());
        assertEquals("FAILED", result.get(0).status());
        assertEquals("S2", result.get(0).failedStage());
    }
}
