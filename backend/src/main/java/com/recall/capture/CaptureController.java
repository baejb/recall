package com.recall.capture;

import com.recall.capture.dto.CaptureRequest;
import com.recall.capture.dto.CaptureResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
