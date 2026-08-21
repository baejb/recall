package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.common.MemoryType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A(답변)의 유형별 기여 — 저장된 근거 필드만 조각으로 낸다. 공유 Composer가 번호·질문·intent를 담당하므로 여기서는 "무엇을 근거로 줄지"만 검증한다.
 * 근거에 없는 문장을 만들지 않는지가 핵심(🔴 근거 없는 생성 금지).
 */
class TroubleshootingAnswerTest {

    private final TroubleshootingAnswer answer = new TroubleshootingAnswer();

    @Test
    @DisplayName("supports()는 TROUBLESHOOTING")
    void supports() {
        assertEquals(MemoryType.TROUBLESHOOTING, answer.supports());
    }

    @Test
    @DisplayName("증상·에러·환경·시도·원인·해결·상태가 모두 근거 조각에 담긴다")
    void rendersAllEvidenceFields() {
        String out =
                answer.render(
                        Map.of(
                                "title",
                                "컨테이너 OOM",
                                "summary",
                                "메모리 한도 초과로 죽었다",
                                "symptom",
                                "컨테이너가 5분마다 죽는다",
                                "error_signature",
                                "OOMKilled exit 137",
                                "environment",
                                "Docker 27 / 2GB limit",
                                "attempts",
                                List.of(
                                        Map.of(
                                                "action",
                                                "docker restart",
                                                "result",
                                                "5분 뒤 재발",
                                                "outcome",
                                                "failed"),
                                        Map.of(
                                                "action",
                                                "한도 4GB 상향",
                                                "result",
                                                "에러 사라짐",
                                                "outcome",
                                                "worked")),
                                "root_cause",
                                "JVM heap이 컨테이너 한도를 넘김",
                                "final_solution",
                                "limit 4GB + MaxRAMPercentage=75",
                                "status",
                                "RESOLVED"));

        assertTrue(out.contains("컨테이너 OOM"), "제목");
        assertTrue(out.contains("메모리 한도 초과로 죽었다"), "요약");
        assertTrue(out.contains("컨테이너가 5분마다 죽는다"), "증상");
        assertTrue(out.contains("OOMKilled exit 137"), "에러 시그니처");
        assertTrue(out.contains("Docker 27 / 2GB limit"), "환경");
        assertTrue(out.contains("JVM heap이 컨테이너 한도를 넘김"), "근본 원인");
        assertTrue(out.contains("limit 4GB + MaxRAMPercentage=75"), "최종 해결");
        assertTrue(out.contains("RESOLVED"), "상태");
    }

    @Test
    @DisplayName("🟠 시도 이력은 실패까지 결과·판정과 함께 근거에 남는다(회상 질문의 핵심)")
    void rendersAttemptsWithOutcome() {
        String out =
                answer.render(
                        Map.of(
                                "title",
                                "t",
                                "attempts",
                                List.of(
                                        Map.of(
                                                "action",
                                                "docker restart",
                                                "result",
                                                "5분 뒤 재발",
                                                "outcome",
                                                "failed"))));

        assertTrue(out.contains("docker restart"), "시도한 조치");
        assertTrue(out.contains("5분 뒤 재발"), "그 결과");
        assertTrue(out.contains("failed"), "성공/실패 판정");
    }

    @Test
    @DisplayName("없는 필드의 라벨은 렌더하지 않는다(빈 값을 근거처럼 보이게 하지 않음)")
    void omitsMissingFields() {
        String out = answer.render(Map.of("title", "제목", "symptom", "죽는다"));

        assertTrue(out.contains("죽는다"));
        assertFalse(out.contains("원인"), "근본 원인이 없으면 라벨도 없다");
        assertFalse(out.contains("해결"), "해결이 없으면 라벨도 없다");
    }

    @Test
    @DisplayName("근거가 아무것도 없으면 (내용 없음) — 지어내지 않는다")
    void emptyCard() {
        assertEquals("(내용 없음)", answer.render(Map.of()));
    }
}
