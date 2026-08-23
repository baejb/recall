package com.recall.memory.type.troubleshooting;

import com.recall.common.type.MemoryType;
import com.recall.memory.type.EmbeddingKind;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.SearchRepresentation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 트러블슈팅(troubleshooting) 유형 검색 표현(R) — PRD §04의 <b>problem/solution 이중 벡터</b>를 낸다.
 *
 * <ul>
 *   <li>{@link EmbeddingKind#PROBLEM} — 증상·에러 메시지·에러 시그니처·환경. "이런 증상이었는데" 류 질문이 걸리는 쪽.
 *   <li>{@link EmbeddingKind#SOLUTION} — 근본 원인·최종 해결. "결국 어떻게 고쳤지" 류 질문이 걸리는 쪽.
 * </ul>
 *
 * <p>문제와 해결을 한 벡터로 합치지 않는 이유는 둘이 서로 다른 질문에 걸려야 하기 때문이다(PRD: "무엇을 임베딩하나가 연관성의 절반"). 미해결 카드는 solution
 * kind를 만들지 않는다 — 빈 텍스트를 임베딩해 의미 없는 벡터를 심지 않는다.
 *
 * <p>attempts는 임베딩하지 않는다(PRD는 이중 벡터로 명시). 시도 이력은 keywords·BM25와 리랭크(RR)로 걸리고, 답변 근거로는 {@link
 * TroubleshootingAnswer}가 온전히 싣는다.
 *
 * <p>kind 순서(problem 먼저)는 의미가 있다 — S4 유사 판정이 유형의 <b>첫 kind</b>를 대표 텍스트로 쓴다(같은 문제인지를 증상·시그니처로 먼저
 * 본다).
 */
@Component
public class TroubleshootingSearchRepresentation implements SearchRepresentation {

    @Override
    public MemoryType supports() {
        return MemoryType.TROUBLESHOOTING;
    }

    @Override
    public Map<String, String> embeddingTexts(MemoryCard card) {
        TroubleshootingCard ts = requireTroubleshooting(card);
        Map<String, String> texts = new LinkedHashMap<>();

        // 필드 이름을 문자열 키로 다시 적지 않는다 — 카드 접근자를 쓰면 스키마 변경이 컴파일 에러로 드러난다.
        String problem =
                join(ts.symptom(), ts.errorMessage(), ts.errorSignature(), ts.environment());
        if (problem.isBlank()) {
            // 증상 계열이 전부 비면 제목이라도 문제 벡터로 남긴다(카드가 검색에서 사라지지 않게).
            problem = ts.title();
        }
        if (!problem.isBlank()) {
            texts.put(EmbeddingKind.PROBLEM, problem);
        }

        String solution = join(ts.rootCause(), ts.finalSolution());
        if (!solution.isBlank()) {
            texts.put(EmbeddingKind.SOLUTION, solution);
        }
        return texts;
    }

    private static TroubleshootingCard requireTroubleshooting(MemoryCard card) {
        if (card instanceof TroubleshootingCard ts) {
            return ts;
        }
        throw new IllegalArgumentException(
                "TROUBLESHOOTING 전략에 다른 유형 카드가 전달됨: "
                        + (card == null ? "null" : card.getClass().getSimpleName()));
    }

    /** 비어있지 않은 값만 줄바꿈으로 잇는다. */
    private String join(String... values) {
        return Stream.of(values)
                .filter(value -> value != null && !value.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
