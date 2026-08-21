package com.recall.llm.provider.openai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAI 채팅 응답 → 텍스트(textFrom)·임베딩 응답 → 벡터(vectorFrom) 추출의 결정론 검증. 실제 HTTP 호출은 부팅 스모크로 확인하고, 여기선
 * 파싱·차원검증만 본다.
 */
class OpenAiClientsTest {

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("OpenAI: choices[0].message.content를 뽑는다")
    void openaiChat() throws Exception {
        String json =
                """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"응답 본문"}}]}
                """;
        var resp = mapper.readValue(json, OpenAiLlmClient.ChatResponse.class);
        assertEquals("응답 본문", OpenAiLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("OpenAI: choices가 비면 예외")
    void openaiChatEmpty() throws Exception {
        var resp = mapper.readValue("{\"choices\":[]}", OpenAiLlmClient.ChatResponse.class);
        assertThrows(IllegalStateException.class, () -> OpenAiLlmClient.textFrom(resp));
    }

    @Test
    @DisplayName("OpenAI: data[0].embedding을 차원대로 뽑는다")
    void openaiEmbedding() throws Exception {
        String json =
                """
                {"data":[{"index":0,"embedding":[0.1,0.2,0.3]}],"model":"text-embedding-3-small"}
                """;
        var resp = mapper.readValue(json, OpenAiEmbeddingClient.EmbeddingResponse.class);
        assertArrayEquals(
                new float[] {0.1f, 0.2f, 0.3f}, OpenAiEmbeddingClient.vectorFrom(resp, 3), 1e-6f);
    }

    @Test
    @DisplayName("OpenAI: 차원이 기대와 다르면 예외(조용한 실패 금지)")
    void openaiEmbeddingDimensionMismatch() throws Exception {
        String json =
                """
                {"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}
                """;
        var resp = mapper.readValue(json, OpenAiEmbeddingClient.EmbeddingResponse.class);
        assertThrows(
                IllegalStateException.class, () -> OpenAiEmbeddingClient.vectorFrom(resp, 1024));
    }

    @Test
    @DisplayName("OpenAI: data가 비면 예외")
    void openaiEmbeddingEmpty() throws Exception {
        var resp = mapper.readValue("{\"data\":[]}", OpenAiEmbeddingClient.EmbeddingResponse.class);
        assertThrows(
                IllegalStateException.class, () -> OpenAiEmbeddingClient.vectorFrom(resp, 1024));
    }
}
