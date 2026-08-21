package com.recall.llm.provider.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gemini 응답 JSON → 텍스트 추출(textFrom)의 결정론 검증. 실제 HTTP 호출은 부팅 스모크로 확인하고, 여기선 파싱만 본다. */
class GoogleLlmClientTest {

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("Gemini: candidates[0].content.parts[0].text를 뽑는다")
    void google() throws Exception {
        String json =
                """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"제미나이 응답"}]}}]}
                """;
        var resp = mapper.readValue(json, GoogleLlmClient.GenerateResponse.class);
        assertEquals("제미나이 응답", GoogleLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("Gemini: candidates가 비면 예외")
    void googleEmpty() throws Exception {
        var resp = mapper.readValue("{\"candidates\":[]}", GoogleLlmClient.GenerateResponse.class);
        assertThrows(IllegalStateException.class, () -> GoogleLlmClient.textFrom(resp));
    }
}
