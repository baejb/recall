package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.memory.type.EmbeddingKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R(검색 표현) troubleshooting — PRD 이중 벡터(problem·solution) 구성은 결정론이라 kind 구성·순서·빈 값 생략을 단위테스트로 고정한다.
 *
 * <p>kind 이름은 {@link EmbeddingKind} 상수로 확인한다 — 테스트가 문자열을 복사해 두면 프로덕션 어휘가 바뀌어도 통과한다.
 */
class TroubleshootingSearchRepresentationTest {

    private final TroubleshootingSearchRepresentation rep =
            new TroubleshootingSearchRepresentation();

    /** 전 필드가 채워진 카드. */
    private static TroubleshootingCard card() {
        return new TroubleshootingCard(
                "컨테이너 OOM",
                "요약",
                List.of(),
                "컨테이너가 5분마다 죽는다",
                "Killed process 1 (java)",
                "OOMKilled exit 137",
                "Docker 27 / 2GB limit",
                List.of(),
                "JVM heap이 한도를 넘김",
                "limit 4GB 상향",
                "RESOLVED");
    }

    @Test
    @DisplayName("problem kind는 증상·에러메시지·시그니처·환경을 합친다")
    void problemKind() {
        String problem = rep.embeddingTexts(card()).get(EmbeddingKind.PROBLEM);

        assertTrue(problem.contains("컨테이너가 5분마다 죽는다"), "증상");
        assertTrue(problem.contains("Killed process 1"), "에러 메시지");
        assertTrue(problem.contains("OOMKilled exit 137"), "에러 시그니처");
        assertTrue(problem.contains("Docker 27 / 2GB limit"), "환경");
    }

    @Test
    @DisplayName("solution kind는 근본원인·최종해결을 합친다")
    void solutionKind() {
        String solution = rep.embeddingTexts(card()).get(EmbeddingKind.SOLUTION);

        assertTrue(solution.contains("JVM heap이 한도를 넘김"), "근본 원인");
        assertTrue(solution.contains("limit 4GB 상향"), "최종 해결");
    }

    @Test
    @DisplayName("PRD 이중 벡터 — kind는 problem·solution 둘뿐이고 problem이 먼저다")
    void onlyTwoKindsInOrder() {
        assertEquals(
                List.of(EmbeddingKind.PROBLEM, EmbeddingKind.SOLUTION),
                List.copyOf(rep.embeddingTexts(card()).keySet()));
    }

    @Test
    @DisplayName("증상이 비면 title로 폴백해 problem kind를 보장한다")
    void problemFallsBackToTitle() {
        TroubleshootingCard titleOnly =
                new TroubleshootingCard(
                        "제목만 있음", "", List.of(), "", "", "", "", List.of(), "", "", null);
        Map<String, String> texts = rep.embeddingTexts(titleOnly);
        assertEquals("제목만 있음", texts.get(EmbeddingKind.PROBLEM));
    }

    @Test
    @DisplayName("미해결 카드는 solution kind를 만들지 않는다(빈 값 kind 생략)")
    void skipsEmptySolution() {
        TroubleshootingCard unresolved =
                new TroubleshootingCard(
                        "", "", List.of(), "죽는다", "", "", "", List.of(), "", "", null);
        Map<String, String> texts = rep.embeddingTexts(unresolved);
        assertTrue(texts.containsKey(EmbeddingKind.PROBLEM));
        assertFalse(texts.containsKey(EmbeddingKind.SOLUTION), "해결이 없으면 solution 벡터를 만들지 않는다");
    }

    @Test
    @DisplayName("전부 비면 빈 맵(임베딩 호출 대상 없음)")
    void emptyWhenAllBlank() {
        TroubleshootingCard blank =
                new TroubleshootingCard(
                        null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(rep.embeddingTexts(blank).isEmpty());
    }
}
