package com.recall.llm.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.llm.LlmProperties;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
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

    private AnthropicLlmClient client() {
        return new AnthropicLlmClient(
                new LlmProperties("anthropic", "sk-test", "claude-opus-4-8", null, 4096));
    }

    @Test
    @DisplayName("consumeStream: data 라인의 text_delta 를 순서대로 onToken 으로 흘린다")
    void consumeStreamEmitsDeltas() throws Exception {
        String sse =
                "event: content_block_delta\n"
                        + "data:"
                        + " {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"안녕\"}}\n"
                        + ": ping\n"
                        + "data:"
                        + " {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"세계\"}}\n";
        List<String> tokens = new ArrayList<>();
        client().consumeStream(new BufferedReader(new StringReader(sse)), tokens::add);
        assertEquals(List.of("안녕", "세계"), tokens);
    }

    @Test
    @DisplayName("consumeStream: 인터럽트되면 하트비트만 와도 소비를 멈추고 예외로 알린다 — 커넥션 누수 방지")
    void consumeStreamStopsWhenInterrupted() {
        // 텍스트 토큰이 하나도 없는(하트비트만) 스트림 — 취소 관측이 토큰 도착에만 걸려 있으면 여기서 못 멈춘다.
        String sse = ": ping\n: ping\n: ping\n";
        List<String> tokens = new ArrayList<>();
        AnthropicLlmClient client = client();
        try {
            Thread.currentThread().interrupt();
            assertThrows(
                    UncheckedIOException.class,
                    () ->
                            client.consumeStream(
                                    new BufferedReader(new StringReader(sse)), tokens::add));
        } finally {
            Thread.interrupted(); // 플래그 정리(테스트 격리)
        }
        assertTrue(tokens.isEmpty(), "취소 시 어떤 토큰도 흘리지 않는다");
    }
}
