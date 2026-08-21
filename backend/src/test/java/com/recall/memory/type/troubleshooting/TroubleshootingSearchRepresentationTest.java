package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.common.MemoryType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 검색 표현(R) — PRD §04의 트러블슈팅 "problem/solution 이중 벡터"를 낸다. 결정론이라 순수 함수 단위테스트로 고정한다(같은 입력=같은 출력). */
class TroubleshootingSearchRepresentationTest {

    private final TroubleshootingSearchRepresentation rep =
            new TroubleshootingSearchRepresentation();

    private static Map<String, Object> card() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "컨테이너 OOM");
        m.put("symptom", "컨테이너가 5분마다 죽는다");
        m.put("error_message", "Killed process 1");
        m.put("error_signature", "OOMKilled exit 137");
        m.put("environment", "Docker 27 / 2GB limit");
        m.put("root_cause", "JVM heap이 한도를 넘김");
        m.put("final_solution", "limit 4GB 상향");
        return m;
    }

    @Test
    @DisplayName("supports()는 TROUBLESHOOTING")
    void supports() {
        assertEquals(MemoryType.TROUBLESHOOTING, rep.supports());
    }

    @Test
    @DisplayName("problem kind는 증상·에러메시지·시그니처·환경을 합친다")
    void problemKind() {
        String problem = rep.embeddingTexts(card()).get("problem");

        assertTrue(problem.contains("컨테이너가 5분마다 죽는다"), "증상");
        assertTrue(problem.contains("Killed process 1"), "에러 메시지");
        assertTrue(problem.contains("OOMKilled exit 137"), "에러 시그니처");
        assertTrue(problem.contains("Docker 27 / 2GB limit"), "환경");
    }

    @Test
    @DisplayName("solution kind는 근본원인·최종해결을 합친다")
    void solutionKind() {
        String solution = rep.embeddingTexts(card()).get("solution");

        assertTrue(solution.contains("JVM heap이 한도를 넘김"), "근본 원인");
        assertTrue(solution.contains("limit 4GB 상향"), "최종 해결");
    }

    @Test
    @DisplayName("PRD 이중 벡터 — kind는 problem·solution 둘뿐이고 problem이 먼저다")
    void onlyTwoKindsInOrder() {
        assertEquals(
                List.of("problem", "solution"), List.copyOf(rep.embeddingTexts(card()).keySet()));
    }

    @Test
    @DisplayName("증상이 비면 title로 폴백해 problem kind를 보장한다")
    void problemFallsBackToTitle() {
        Map<String, String> texts = rep.embeddingTexts(Map.of("title", "제목만 있음"));
        assertEquals("제목만 있음", texts.get("problem"));
    }

    @Test
    @DisplayName("미해결 카드는 solution kind를 만들지 않는다(빈 값 kind 생략)")
    void skipsEmptySolution() {
        Map<String, String> texts =
                rep.embeddingTexts(
                        Map.of("symptom", "죽는다", "root_cause", "", "final_solution", ""));
        assertTrue(texts.containsKey("problem"));
        assertFalse(texts.containsKey("solution"), "해결이 없으면 solution 벡터를 만들지 않는다");
    }

    @Test
    @DisplayName("전부 비면 빈 맵(임베딩 호출 대상 없음)")
    void emptyWhenAllBlank() {
        assertTrue(rep.embeddingTexts(Map.of()).isEmpty());
    }
}
