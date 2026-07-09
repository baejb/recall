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

@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;

    public CaptureController(CaptureService captureService) {
        this.captureService = captureService;
    }

    @PostMapping
    public ResponseEntity<CaptureResponse> capture(@Valid @RequestBody CaptureRequest request) {
        Long id = captureService.capture(request.text(), request.sourceTypeOrDefault());
        return ResponseEntity.accepted()
                .body(new CaptureResponse(id, "extracting"));
    }
}
