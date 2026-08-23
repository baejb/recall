package com.recall.memory.type.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.memory.type.troubleshooting.TroubleshootingCard;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A(답변)의 knowledge 기여 — 답변 프롬프트의 근거 조각이 공유 Composer에서 이 전략으로 옮겨왔으므로(유형별 필드 하드코딩 제거), 기존에 프롬프트가 담던
 * 제목·요약·사실이 그대로 나오는지 회귀로 고정한다.
 *
 * <p>입력이 {@code Map} 이 아니라 {@link KnowledgeCard} 인 것은 SPI 경계가 카드 타입으로 올라갔기 때문이다 — 필드 이름을 테스트가 문자열로
 * 다시 적을 필요가 없다.
 */
class KnowledgeAnswerTest {

    private final KnowledgeAnswer answer = new KnowledgeAnswer();

    @Test
    @DisplayName("제목·요약·사실이 근거 조각에 담긴다(공유 프롬프트가 담던 것과 동일)")
    void rendersTitleSummaryFacts() {
        String out =
                answer.render(
                        new KnowledgeCard(
                                "게이트웨이 분리",
                                "토폴로지 분리는 끝났다",
                                List.of(),
                                List.of("별도 배포 단위", "REST·Kafka로만 연결"),
                                null));

        assertTrue(out.contains("게이트웨이 분리"), "제목");
        assertTrue(out.contains("토폴로지 분리는 끝났다"), "요약");
        assertTrue(out.contains("별도 배포 단위"), "사실1");
        assertTrue(out.contains("REST·Kafka로만 연결"), "사실2");
    }

    @Test
    @DisplayName("사실이 없으면 사실 라벨을 렌더하지 않는다")
    void omitsEmptyFacts() {
        String out = answer.render(new KnowledgeCard("제목", "요약", List.of(), List.of(), null));

        assertTrue(out.contains("요약"));
        assertTrue(!out.contains("사실"), "빈 사실 목록에 라벨을 붙이지 않는다");
    }

    @Test
    @DisplayName("근거가 아무것도 없으면 (내용 없음) — 지어내지 않는다")
    void emptyCard() {
        assertEquals("(내용 없음)", answer.render(new KnowledgeCard(null, null, null, null, null)));
    }

    @Test
    @DisplayName("다른 유형 카드가 오면 조용히 렌더하지 않고 즉시 드러낸다(배선 버그)")
    void rejectsForeignCard() {
        // 레지스트리 디스패치가 어긋나면 근거가 엉뚱하게 비는 대신 예외로 드러나야 한다.
        TroubleshootingCard foreign =
                new TroubleshootingCard(
                        "t", "s", List.of(), "sym", "", "", "", List.of(), "", "", null);
        assertThrows(IllegalArgumentException.class, () -> answer.render(foreign));
    }
}
