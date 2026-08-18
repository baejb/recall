package com.recall.query;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.AiNotConfiguredException;
import com.recall.common.CurrentUserProvider;
import com.recall.common.MemoryType;
import com.recall.llm.LlmClient;
import com.recall.llm.StubEmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.Memory;
import com.recall.memory.type.AnswerContribution;
import com.recall.query.dto.QueryRequest;
import com.recall.search.HybridSearchService;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 조회(답변) 경로가 {@link UserAiContext}를 관통하며 "미설정 차단(409)"과 "설정 완료 후 외부 API 실패(격하)"를 하나의 {@code
 * llmReady()}로 섞지 않는지 회귀 고정한다(불변 원칙: 조용한 실패 금지, 설계 문서 §4).
 *
 * <p>차단 케이스는 실제 {@link QueryController}·{@link com.recall.llm.AiContextFactory}·{@link
 * com.recall.settings.SettingsService}를 그대로 태워 "SSE가 200을 먼저 돌려주기 전에 요청 스레드에서 차단돼야 한다"는 계약을 검증한다.
 * 격하 케이스는 실제 LLM 호출 없이(네트워크 비의존) {@link AnswerStreamer}/{@link QueryPipeline}을 직접 구성해, 설정은
 * 완료(chatReady=true)됐지만 외부 호출 자체가 실패하는 상황을 재현한다.
 */
@SpringBootTest
class QueryContextIsolationTest {

    @Autowired QueryController controller;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider currentUser;

    long user2;

    @BeforeEach
    void seed() {
        user2 =
                jdbc.queryForObject(
                        "INSERT INTO app_user(provider,subject) VALUES('test','query-isolation2')"
                                + " RETURNING id",
                        Long.class);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM model_setting WHERE user_id=?", user2);
        jdbc.update("DELETE FROM app_user WHERE id=?", user2);
    }

    @Test
    @Tag("release-gate")
    @DisplayName("chat 미설정 사용자의 답변 요청은 요청 스레드에서 즉시 차단(409, 격하 아님)")
    void unconfiguredChatBlocksAnswer() {
        when(currentUser.currentUserId()).thenReturn(user2); // user2는 model_setting 행이 없다 = 미설정

        assertThrows(AiNotConfiguredException.class, () -> controller.query(new QueryRequest("q")));
    }

    @Test
    @DisplayName(
            "설정 완료(chatReady) 후 외부 LLM 호출 실패는 기존 격하(fallback) 유지 — AiNotConfiguredException 아님")
    void externalFailureStillDegrades() throws Exception {
        HybridSearchService search = mock(HybridSearchService.class);
        LlmClient failingLlm =
                new LlmClient() {
                    @Override
                    public String complete(String systemPrompt, String userPrompt) {
                        throw new RuntimeException("external boom");
                    }

                    @Override
                    public void completeStream(
                            String systemPrompt, String userPrompt, Consumer<String> onToken) {
                        throw new RuntimeException("external boom");
                    }
                };
        UserAiContext ctx =
                new UserAiContext(1L, failingLlm, new StubEmbeddingClient(), true, false);

        Memory m1 = Memory.transientCard(MemoryType.KNOWLEDGE, "t1", "{\"title\":\"t1\"}");
        Memory m2 = Memory.transientCard(MemoryType.KNOWLEDGE, "t2", "{\"title\":\"t2\"}");
        when(search.search(eq("q"), eq(MemoryType.KNOWLEDGE), eq(ctx))).thenReturn(List.of(m1, m2));

        AnswerContribution answer =
                new AnswerContribution() {
                    @Override
                    public MemoryType supports() {
                        return MemoryType.KNOWLEDGE;
                    }

                    @Override
                    public String render(Map<String, Object> memory) {
                        return "요약:" + memory.get("title");
                    }
                };
        QueryPipeline pipeline = new QueryPipeline(search, List.of(answer));
        AnswerStreamer streamer = new AnswerStreamer(pipeline);
        SseEmitter emitter = mock(SseEmitter.class);

        streamer.emit(emitter, "q", ctx);

        // chatReady=true(설정 완료)이므로 AiNotConfiguredException 없이, 기존 격하 정책(요약 fallback)으로
        // 완료돼야 한다 — completeWithError(에러 SSE)로 새나가지 않는다.
        verify(emitter, never()).completeWithError(any());
        verify(emitter).complete();
        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
