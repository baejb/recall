package com.recall.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 원본 캡처 조회(Unit Q1) 통합 — memory 상세의 근거 확인용 {@code GET /api/captures/{id}}. rawText 는 캡처 시점에 이미 마스킹돼
 * 저장되므로 그대로 노출해도 안전하다(CaptureService.capture 참고). 없는 id 는 404, 그리고 기존 {@code
 * /api/captures/active}(리터럴)와 신규 {@code /{id}}(Long 경로변수) 라우트가 서로를 가리지 않는지도 함께 본다. 공유 테이블을 쓰므로 만든
 * 행은 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaptureRawFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CaptureRepository captureRepository;

    private final List<Long> createdCaptures = new ArrayList<>();

    @AfterEach
    void cleanup() {
        captureRepository.deleteAllById(createdCaptures);
        createdCaptures.clear();
    }

    @Test
    void rawEndpointReturnsMaskedRawTextAndSourceType() throws Exception {
        Capture capture = captureRepository.save(new Capture("chat", "마스킹된 원문 [REDACTED]", "[]"));
        createdCaptures.add(capture.getId());

        mockMvc.perform(get("/api/captures/{id}", capture.getId()))
                .andExpect(status().isOk())
                .andExpect(
                        result -> {
                            String json = result.getResponse().getContentAsString();
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .readTree(json);
                            assertEquals(capture.getId(), node.get("id").asLong());
                            assertEquals("chat", node.get("sourceType").asText());
                            assertEquals("마스킹된 원문 [REDACTED]", node.get("rawText").asText());
                            assertTrue(node.hasNonNull("createdAt"));
                        });
    }

    @Test
    void rawOfMissingCaptureReturns404() throws Exception {
        long missingId = maxCaptureId() + 1000;

        mockMvc.perform(get("/api/captures/{id}", missingId)).andExpect(status().isNotFound());
    }

    @Test
    void activeAndRawRoutesCoexistWithoutShadowing() throws Exception {
        Capture capture = captureRepository.save(new Capture("chat", "라우트 공존 확인용 원문", "[]"));
        createdCaptures.add(capture.getId());

        // 리터럴 라우트(/active)가 여전히 상태 목록(200)을 반환한다.
        mockMvc.perform(get("/api/captures/active")).andExpect(status().isOk());

        // 숫자 경로변수 라우트(/{id})가 새 원문 DTO(200)를 반환한다 — 서로 가리지 않는다.
        mockMvc.perform(get("/api/captures/{id}", capture.getId()))
                .andExpect(status().isOk())
                .andExpect(
                        result -> {
                            String json = result.getResponse().getContentAsString();
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .readTree(json);
                            assertEquals(capture.getId(), node.get("id").asLong());
                        });
    }

    private long maxCaptureId() {
        return captureRepository.findAll().stream().mapToLong(Capture::getId).max().orElse(0L);
    }
}
