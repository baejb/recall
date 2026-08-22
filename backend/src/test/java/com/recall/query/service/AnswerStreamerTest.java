package com.recall.query.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.type.MemoryType;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.LlmClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.service.entity.Memory;
import com.recall.query.controller.dto.AnswerFragment;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 답변 스트리밍 오케스트레이션 — 근거 없음/합성 실패(격하)/합성 성공 세 경로의 분기. 소유자·chat/embedding 클라이언트는 {@link
 * UserAiContext}로 관통한다(더 이상 literal userId를 받지 않는다). chat 미설정 차단(409)은 {@code QueryController}가 스트림
 * 시작 전에 걸러내므로, 이 클래스는 항상 chatReady 인 ctx로 호출된다는 전제 — 여기서 재현하는 건 설정 완료 후의 "외부 호출 실패 → 격하" 뿐이다.
 */
class AnswerStreamerTest {

    private static final UserAiContext CTX =
            new UserAiContext(1L, mock(LlmClient.class), mock(EmbeddingClient.class), true, false);

    private Memory memory() {
        return Memory.transientCard(MemoryType.KNOWLEDGE, "제목", "{}");
    }

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 근거 없음 → LLM 미호출, '기록 없음'만 보내고 완료(근거 없는 생성 금지)")
    void noEvidenceSkipsLlm() throws Exception {
        QueryPipeline pipeline = mock(QueryPipeline.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(pipeline.classify("q", CTX)).thenReturn(MemoryType.KNOWLEDGE);
        when(pipeline.retrieve("q", MemoryType.KNOWLEDGE, CTX)).thenReturn(List.of());

        new AnswerStreamer(pipeline).emit(emitter, "q", CTX);

        verify(pipeline, never()).rerank(any(), anyList(), any());
        verify(pipeline, never()).composeStreaming(any(), anyList(), any(), any());
        verify(pipeline, never()).fallbackFragments(any());
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    @DisplayName("설정 완료 후 외부 합성(A) 호출 실패 → 요약 격하(fallbackFragments), 에러로 새나가지 않음")
    void composeFailureDegradesToFallback() throws Exception {
        QueryPipeline pipeline = mock(QueryPipeline.class);
        SseEmitter emitter = mock(SseEmitter.class);
        List<Memory> candidates = List.of(memory());
        when(pipeline.classify("q", CTX)).thenReturn(MemoryType.KNOWLEDGE);
        when(pipeline.retrieve("q", MemoryType.KNOWLEDGE, CTX)).thenReturn(candidates);
        when(pipeline.rerank(eq("q"), anyList(), eq(CTX))).thenReturn(candidates);
        doThrow(new RuntimeException("external boom"))
                .when(pipeline)
                .composeStreaming(eq("q"), anyList(), any(), eq(CTX));
        when(pipeline.fallbackFragments(candidates))
                .thenReturn(List.of(new AnswerFragment("요약", 1L)));

        new AnswerStreamer(pipeline).emit(emitter, "q", CTX);

        verify(pipeline).rerank(eq("q"), anyList(), eq(CTX));
        verify(pipeline).fallbackFragments(candidates);
        verify(emitter, never()).completeWithError(any());
        verify(emitter).complete();
    }

    @Test
    @DisplayName("합성 성공 → 근거로 합성 스트리밍, 요약 격하는 안 함")
    void composeSucceeds() throws Exception {
        QueryPipeline pipeline = mock(QueryPipeline.class);
        SseEmitter emitter = mock(SseEmitter.class);
        List<Memory> candidates = List.of(memory());
        when(pipeline.classify("q", CTX)).thenReturn(MemoryType.KNOWLEDGE);
        when(pipeline.retrieve("q", MemoryType.KNOWLEDGE, CTX)).thenReturn(candidates);
        when(pipeline.rerank(eq("q"), anyList(), eq(CTX))).thenReturn(candidates);
        doAnswer(
                        inv -> {
                            Consumer<String> sink = inv.getArgument(2);
                            sink.accept("합성된 답변");
                            return null;
                        })
                .when(pipeline)
                .composeStreaming(eq("q"), anyList(), any(), eq(CTX));

        new AnswerStreamer(pipeline).emit(emitter, "q", CTX);

        verify(pipeline).composeStreaming(eq("q"), anyList(), any(), eq(CTX));
        verify(pipeline, never()).fallbackFragments(any());
        verify(emitter).complete();
    }

    @Test
    @DisplayName("isNoRecord: '기록 없음'(마침표·공백 변형 포함) 판정")
    void detectsNoRecord() {
        org.junit.jupiter.api.Assertions.assertTrue(AnswerStreamer.isNoRecord("기록 없음"));
        org.junit.jupiter.api.Assertions.assertTrue(AnswerStreamer.isNoRecord("기록 없음."));
        org.junit.jupiter.api.Assertions.assertTrue(AnswerStreamer.isNoRecord("  기록 없음  "));
        org.junit.jupiter.api.Assertions.assertFalse(
                AnswerStreamer.isNoRecord("기록 없음이 아니라 실제 답입니다"));
    }
}
