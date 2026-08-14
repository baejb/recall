package com.recall.llm.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Anthropic 어댑터 — complete()의 응답 텍스트 추출(textFrom)과 스트리밍 SSE 델타 파싱(parseTextDelta). */
class AnthropicLlmClientTest {

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("complete: 첫 text 블록을 뽑는다")
    void textFromFirstBlock() throws Exception {
        String json =
                """
                {"content":[{"type":"text","text":"추출된 결과"}],"model":"claude-opus-4-8"}
                """;
        var resp = mapper.readValue(json, AnthropicLlmClient.MessagesResponse.class);
        assertEquals("추출된 결과", AnthropicLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("complete: text 블록이 없으면 예외")
    void textFromEmptyThrows() throws Exception {
        var resp = mapper.readValue("{\"content\":[]}", AnthropicLlmClient.MessagesResponse.class);
        assertThrows(IllegalStateException.class, () -> AnthropicLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("content_block_delta의 text_delta에서 텍스트를 뽑는다")
    void extractsTextDelta() {
        String data =
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"안녕\"}}";
        assertEquals(Optional.of("안녕"), AnthropicLlmClient.parseTextDelta(data, mapper));
    }

    @Test
    @DisplayName("델타가 아닌 이벤트(message_start·다른 delta 타입)는 무시")
    void ignoresNonTextDelta() {
        assertTrue(
                AnthropicLlmClient.parseTextDelta("{\"type\":\"message_start\"}", mapper)
                        .isEmpty());
        assertTrue(
                AnthropicLlmClient.parseTextDelta(
                                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\"}}",
                                mapper)
                        .isEmpty());
    }

    @Test
    @DisplayName("빈 라인·JSON 아닌 라인은 무시(하트비트 등)")
    void ignoresMalformed() {
        assertTrue(AnthropicLlmClient.parseTextDelta("", mapper).isEmpty());
        assertTrue(AnthropicLlmClient.parseTextDelta("not-json", mapper).isEmpty());
    }
}
