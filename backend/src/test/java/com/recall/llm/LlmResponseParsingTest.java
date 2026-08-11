package com.recall.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** provider별 응답 JSON → 텍스트 추출(textFrom)의 결정론 검증. 실제 HTTP 호출은 부팅 스모크로 확인하고, 여기선 파싱만 본다. */
class LlmResponseParsingTest {

    private final ObjectMapper mapper =
            new ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    @Test
    @DisplayName("Anthropic: 첫 text 블록을 뽑는다")
    void anthropic() throws Exception {
        String json =
                """
                {"content":[{"type":"text","text":"추출된 결과"}],"model":"claude-opus-4-8"}
                """;
        var resp = mapper.readValue(json, AnthropicLlmClient.MessagesResponse.class);
        assertEquals("추출된 결과", AnthropicLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("Anthropic: text 블록이 없으면 예외")
    void anthropicNoText() throws Exception {
        var resp = mapper.readValue("{\"content\":[]}", AnthropicLlmClient.MessagesResponse.class);
        assertThrows(IllegalStateException.class, () -> AnthropicLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("OpenAI: choices[0].message.content를 뽑는다")
    void openai() throws Exception {
        String json =
                """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"응답 본문"}}]}
                """;
        var resp = mapper.readValue(json, OpenAiLlmClient.ChatResponse.class);
        assertEquals("응답 본문", OpenAiLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("OpenAI: choices가 비면 예외")
    void openaiEmpty() throws Exception {
        var resp = mapper.readValue("{\"choices\":[]}", OpenAiLlmClient.ChatResponse.class);
        assertThrows(IllegalStateException.class, () -> OpenAiLlmClient.textFrom(resp));
    }

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
