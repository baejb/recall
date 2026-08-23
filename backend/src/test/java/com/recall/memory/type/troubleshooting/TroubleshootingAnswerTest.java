package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.memory.type.troubleshooting.TroubleshootingCard.Attempt;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A(답변)의 troubleshooting 기여 — 어떤 필드를 근거로 내놓는지가 이 유형의 회상 품질을 결정하므로, 전 필드 노출·실패 시도 보존·빈 필드 라벨 생략을
 * 단위테스트로 고정한다.
 *
 * <p>입력이 {@code Map} 이 아니라 {@link TroubleshootingCard} 인 것은 SPI 경계가 카드 타입으로 올라갔기 때문이다 — 필드 이름과
 * attempts 항목 모양을 테스트가 중첩 Map 으로 다시 조립할 필요가 없다.
 */
class TroubleshootingAnswerTest {

    private final TroubleshootingAnswer answer = new TroubleshootingAnswer();

    @Test
    @DisplayName("증상·에러·환경·시도·원인·해결·상태가 모두 근거 조각에 담긴다")
    void rendersAllEvidenceFields() {
        String out =
                answer.render(
                        new TroubleshootingCard(
                                "컨테이너 OOM",
                                "메모리 한도 초과로 죽었다",
                                List.of(),
                                "컨테이너가 5분마다 죽는다",
                                "",
                                "OOMKilled exit 137",
                                "Docker 27 / 2GB limit",
                                List.of(
                                        new Attempt("docker restart", "5분 뒤 재발", "failed"),
                                        new Attempt("한도 4GB 상향", "에러 사라짐", "worked")),
                                "JVM heap이 컨테이너 한도를 넘김",
                                "limit 4GB + MaxRAMPercentage=75",
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
                        new TroubleshootingCard(
                                "t",
                                "",
                                List.of(),
                                "",
                                "",
                                "",
                                "",
                                List.of(new Attempt("docker restart", "5분 뒤 재발", "failed")),
                                "",
                                "",
                                null));

        assertTrue(out.contains("docker restart"), "시도한 조치");
        assertTrue(out.contains("5분 뒤 재발"), "그 결과");
        assertTrue(out.contains("failed"), "성공/실패 판정");
    }

    @Test
    @DisplayName("없는 필드의 라벨은 렌더하지 않는다(빈 값을 근거처럼 보이게 하지 않음)")
    void omitsMissingFields() {
        String out =
                answer.render(
                        new TroubleshootingCard(
                                "제목",
                                "",
                                List.of(),
                                "죽는다",
                                "",
                                "",
                                "",
                                List.of(),
                                "",
                                "",
                                "UNRESOLVED"));

        assertTrue(out.contains("죽는다"));
        assertFalse(out.contains("원인"), "근본 원인이 없으면 라벨도 없다");
        assertFalse(out.contains("해결"), "해결이 없으면 라벨도 없다");
    }

    @Test
    @DisplayName("근거가 아무것도 없으면 상태만 남는다 — 없는 내용을 지어내지 않는다")
    void emptyCard() {
        // status 는 카드 스키마가 항상 채우는 필드다(모르는 값은 UNRESOLVED — 해결됐다고 단정하지 않는다).
        // 그래서 "전부 빈 카드"에도 상태 줄은 남는다. Map 경계였을 때는 status 키 자체가 없어 (내용 없음)이었다.
        String out =
                answer.render(
                        new TroubleshootingCard(
                                null, null, null, null, null, null, null, null, null, null, null));
        assertTrue(out.contains("UNRESOLVED"), "상태는 항상 정규화돼 남는다");
        assertFalse(out.contains("증상"), "값 없는 필드의 라벨은 렌더하지 않는다");
    }
}
