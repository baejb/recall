package com.recall.llm.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Anthropic 응답 JSON → 텍스트 추출(textFrom)의 결정론 검증. 실제 HTTP 호출은 부팅 스모크로 확인하고, 여기선 파싱만 본다. */
class AnthropicLlmClientTest {

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
}
