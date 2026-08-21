package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.common.MemoryType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A(답변)의 knowledge 기여 — 답변 프롬프트의 근거 조각이 공유 Composer에서 이 전략으로 옮겨왔으므로(유형별 필드 하드코딩 제거), 기존에 프롬프트가
 * 담던 제목·요약·사실이 그대로 나오는지 회귀로 고정한다.
 */
class KnowledgeAnswerTest {

    private final KnowledgeAnswer answer = new KnowledgeAnswer();

    @Test
    @DisplayName("supports()는 KNOWLEDGE")
    void supports() {
        assertEquals(MemoryType.KNOWLEDGE, answer.supports());
    }

    @Test
    @DisplayName("제목·요약·사실이 근거 조각에 담긴다(공유 프롬프트가 담던 것과 동일)")
    void rendersTitleSummaryFacts() {
        String out =
                answer.render(
                        Map.of(
                                "title",
                                "게이트웨이 분리",
                                "summary",
                                "토폴로지 분리는 끝났다",
                                "facts",
                                List.of("별도 배포 단위", "REST·Kafka로만 연결")));

        assertTrue(out.contains("게이트웨이 분리"), "제목");
        assertTrue(out.contains("토폴로지 분리는 끝났다"), "요약");
        assertTrue(out.contains("별도 배포 단위"), "사실1");
        assertTrue(out.contains("REST·Kafka로만 연결"), "사실2");
    }

    @Test
    @DisplayName("사실이 없으면 사실 라벨을 렌더하지 않는다")
    void omitsEmptyFacts() {
        String out = answer.render(Map.of("title", "제목", "summary", "요약", "facts", List.of()));

        assertTrue(out.contains("요약"));
        assertTrue(!out.contains("사실"), "빈 사실 목록에 라벨을 붙이지 않는다");
    }

    @Test
    @DisplayName("근거가 아무것도 없으면 (내용 없음) — 지어내지 않는다")
    void emptyCard() {
        assertEquals("(내용 없음)", answer.render(Map.of()));
    }
}
