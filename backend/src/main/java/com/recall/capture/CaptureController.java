package com.recall.capture;

import com.recall.capture.dto.CaptureRawResponse;
import com.recall.capture.dto.CaptureRequest;
import com.recall.capture.dto.CaptureResponse;
import com.recall.capture.dto.CaptureStatusResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 원문 저장 입구. HTTP 변환만 담당하고 로직은 서비스로 넘긴다. */
@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;

    public CaptureController(CaptureService captureService) {
        this.captureService = captureService;
    }

    @PostMapping
    public ResponseEntity<CaptureResponse> capture(@Valid @RequestBody CaptureRequest request) {
        Long captureId = captureService.capture(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CaptureResponse(captureId, "accepted"));
    }

    /** 처리 중/실패한(아직 검토 대기함 전) 캡처 목록 — UI 의 "정리 중/실패" 표시용. 원문은 포함하지 않는다. */
    @GetMapping("/active")
    public List<CaptureStatusResponse> active() {
        return captureService.activeCaptures();
    }

    /**
     * 원본 캡처(마스킹된 원문) 조회 — memory 상세의 근거 확인용. {@code /active}는 리터럴 경로라 이 {@code /{id}}(Long) 라우트와
     * 겹치지 않는다(스프링이 정확한 리터럴을 우선 매치하고, 애초에 "active"는 Long으로 바인딩되지 않는다).
     */
    @GetMapping("/{id}")
    public CaptureRawResponse raw(@PathVariable Long id) {
        return captureService.getRaw(id);
    }
}
