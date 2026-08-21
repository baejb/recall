package com.recall.memory.type.troubleshooting;

import com.recall.common.MemoryType;
import com.recall.memory.type.PlanContribution;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 트러블슈팅(troubleshooting) 유형 검색 계획 기여(P) — 채널 가중치를 준다. PRD §04: 트러블슈팅은 에러 코드·예외명·명령어처럼 <b>정확 토큰
 * 매칭</b>이 벡터보다 강하므로 키워드(BM25) 채널을 주 채널로, 벡터를 보조로 둔다(지식 유형과 정반대).
 *
 * <p>값은 PRD §2.2 플랜 예시(memory_bm25 2.0 · memory_vector 1.2)를 그대로 시작점으로 삼았다. 라벨셋 fit로 튜닝할 대상이며 바꿀 때는
 * 커밋에 근거를 남긴다(매직넘버 금지 원칙 — 값이 아니라 상수 + 근거로 둔다).
 *
 * <p>PRD가 정의한 exact·raw_bm25·raw_vector 채널은 아직 검색기(R)에 구현돼 있지 않아 여기서 이름을 주지 않는다 — 없는 채널에 가중치를 주면
 * 융합에서 조용히 무시돼 "설정했는데 안 먹는" 상태가 된다(조용한 실패 금지).
 */
@Component
public class TroubleshootingPlanContribution implements PlanContribution {

    /** 정확한 단어 일치(BM25)가 주 채널 — 에러 시그니처·예외명·명령어. */
    private static final double MEMORY_BM25_WEIGHT = 2.0;

    /** 의미 유사(벡터)는 보조 채널 — 표현이 다른 증상 서술을 잡는다. */
    private static final double MEMORY_VECTOR_WEIGHT = 1.2;

    @Override
    public MemoryType supports() {
        return MemoryType.TROUBLESHOOTING;
    }

    @Override
    public Map<String, Double> channelWeights() {
        return Map.of(
                "memory_bm25", MEMORY_BM25_WEIGHT,
                "memory_vector", MEMORY_VECTOR_WEIGHT);
    }
}
