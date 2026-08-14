package com.recall.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.common.MemoryType;
import com.recall.memory.Memory;
import com.recall.query.dto.AnswerFragment;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 답변 스트리밍 오케스트레이션 — 근거 없음/LLM 미가용/LLM 가용 세 경로의 분기. */
class AnswerStreamerTest {

    private Memory memory() {
        return new Memory(null, MemoryType.KNOWLEDGE, "제목", "{}");
    }

    @Test
    @DisplayName("🔴 근거 없음 → LLM 미호출, '기록 없음'만 보내고 완료(근거 없는 생성 금지)")
    void noEvidenceSkipsLlm() throws Exception {
        QueryPipeline pipeline = mock(QueryPipeline.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(pipeline.retrieve("q")).thenReturn(List.of());

        new AnswerStreamer(pipeline).emit(emitter, "q");

        verify(pipeline, never()).llmReady();
        verify(pipeline, never()).composeStreaming(any(), anyList(), any());
        verify(pipeline, never()).fallbackFragments(any());
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    @DisplayName("LLM 미가용 → 합성 대신 요약 격하(fallbackFragments)")
    void llmUnavailableDegradesToFallback() throws Exception {
        QueryPipeline pipeline = mock(QueryPipeline.class);
        SseEmitter emitter = mock(SseEmitter.class);
        List<Memory> candidates = List.of(memory());
        when(pipeline.retrieve("q")).thenReturn(candidates);
        when(pipeline.llmReady()).thenReturn(false);
        when(pipeline.fallbackFragments(candidates))
                .thenReturn(List.of(new AnswerFragment("요약", 1L)));

        new AnswerStreamer(pipeline).emit(emitter, "q");

        verify(pipeline, never()).composeStreaming(any(), anyList(), any());
        verify(pipeline).fallbackFragments(candidates);
        verify(emitter).complete();
    }

    @Test
    @DisplayName("LLM 가용 → 근거로 합성 스트리밍, 요약 격하는 안 함")
    void llmAvailableComposes() throws Exception {
        QueryPipeline pipeline = mock(QueryPipeline.class);
        SseEmitter emitter = mock(SseEmitter.class);
        List<Memory> candidates = List.of(memory());
        when(pipeline.retrieve("q")).thenReturn(candidates);
        when(pipeline.llmReady()).thenReturn(true);
        doAnswer(
                        inv -> {
                            Consumer<String> sink = inv.getArgument(2);
                            sink.accept("합성된 답변");
                            return null;
                        })
                .when(pipeline)
                .composeStreaming(eq("q"), anyList(), any());

        new AnswerStreamer(pipeline).emit(emitter, "q");

        verify(pipeline).composeStreaming(eq("q"), anyList(), any());
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
