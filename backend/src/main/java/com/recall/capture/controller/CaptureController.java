package com.recall.capture.controller;

import com.recall.capture.controller.dto.CaptureRawResponse;
import com.recall.capture.controller.dto.CaptureRequest;
import com.recall.capture.controller.dto.CaptureResponse;
import com.recall.capture.controller.dto.CaptureStatusResponse;
import com.recall.capture.service.CaptureService;
import com.recall.common.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 원문 저장 입구. HTTP 변환만 담당하고 로직은 서비스로 넘긴다. */
@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;

    public CaptureController(CaptureService captureService) {
        this.captureService = captureService;
    }

    /**
     * 원문 접수 — 202. 저장 경로는 비동기라(원문 커밋만 동기) 완료가 아니라 접수를 알린다.
     *
     * <p>상태 코드는 {@code @ResponseStatus} 로 선언한다 — 전에는 {@code ResponseEntity} 를 손으로 조립해서, 본문 타입이 공통
     * 형식인지 원본인지 시그니처만 봐선 알 수 없었다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CaptureResponse> capture(@Valid @RequestBody CaptureRequest request) {
        return ApiResponse.ok(CaptureResponse.accepted(captureService.capture(request)));
    }

    /** 처리 중/실패한(아직 검토 대기함 전) 캡처 목록 — UI 의 "정리 중/실패" 표시용. 원문은 포함하지 않는다. */
    @GetMapping("/active")
    public ApiResponse<List<CaptureStatusResponse>> active() {
        return ApiResponse.ok(captureService.activeCaptures());
    }

    /**
     * 원본 캡처(마스킹된 원문) 조회 — memory 상세의 근거 확인용. {@code /active}는 리터럴 경로라 이 {@code /{id}}(Long) 라우트와
     * 겹치지 않는다(스프링이 정확한 리터럴을 우선 매치하고, 애초에 "active"는 Long으로 바인딩되지 않는다).
     */
    @GetMapping("/{id}")
    public ApiResponse<CaptureRawResponse> raw(@PathVariable Long id) {
        return ApiResponse.ok(captureService.getRaw(id));
    }
}
