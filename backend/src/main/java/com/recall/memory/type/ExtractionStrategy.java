package com.recall.memory.type;

import com.recall.common.type.TypeStrategy;
import com.recall.llm.UserAiContext;
import java.util.List;

/**
 * S2 구조화 추출 — 유형별 스키마로 마스킹된 원문을 구조화한다(예: 지식=facts/document,
 * 트러블슈팅=symptom/root_cause/attempts/status).
 */
public interface ExtractionStrategy extends TypeStrategy {

    /**
     * 마스킹된 원문 → 유형별 카드(승인 전 검토 대기함에 올라갈 후보). LLM 호출은 {@code ctx.requireChat()}로 얻은 클라이언트만 쓴다 — 전역
     * 싱글턴이 아니라 capture 소유자에 바인딩된 클라이언트다(사용자별 provider/키 교차유출 방지). 저장 파이프라인은 이 전략에 도달하기 전에 이미 {@code
     * ctx.chatReady()}를 확인하므로 (StorePipeline의 context 게이트), 정상 흐름에선 {@code requireChat()}이 던지지 않는다.
     *
     * @param maskedText M0 마스킹을 이미 거친 원문
     * @param ctx capture 소유자에 바인딩된 AI 컨텍스트
     */
    MemoryCard extract(String maskedText, UserAiContext ctx);

    /**
     * 저장된 {@code structured} JSON을 이 유형의 카드로 되읽을 때 쓸 스키마 타입.
     *
     * <p><b>왜 필요한가</b> — 카드를 읽는 코드는 공유 코드(memory·review·query·search 모듈)에 있고, 그들은 유형별 카드 클래스를 몰라야
     * 한다. 이 메서드가 그 간극을 메운다: 공유 코드는 {@link CardCodec}에 유형만 주고, 코덱이 여기서 얻은 타입으로 역직렬화한다. 덕분에 저장된 카드를
     * 다시 읽는 모든 경로가 <b>카드 생성자의 정규화를 반드시 거친다</b> — 전에는 {@code Map<String,Object>}로 그냥 읽어서, 예컨대 S3 긴맥락
     * 병합이 만든 카드는 status·outcome 정규화와 error_signature→keywords 병합을 <b>한 번도 통과하지 않고</b> DB·API까지 갔다.
     */
    Class<? extends MemoryCard> cardType();

    /**
     * 긴맥락 병합(S3) 결과의 <b>유형별 후처리</b> — 조각 단위로는 알 수 없던 내부 정합성을 유형이 직접 맞춘다. 기본은 그대로 반환.
     *
     * <p><b>왜 이 훅이 필요한가</b> — S3 병합기는 스키마를 몰라야 하므로(가드레일 2) 스칼라를 "첫 non-blank"로 고른다. 서술 필드
     * (title·summary·document)에는 맞는 규칙이지만 <b>대화의 결말</b>을 담은 필드에는 맞지 않는다: 트러블슈팅의 앞 조각은 아직 해결 전이라
     * {@code status=UNRESOLVED}이고 결말은 뒤에 있다.
     *
     * <p><b>"마지막 조각 값을 쓴다"는 규칙은 오답이었다</b>(2026-08-22 폐기, 이전 {@code lastWinsFields()}). 조각 추출은
     * status 를 <b>항상</b> non-blank 로 낸다(카드 생성자가 모르는 값을 UNRESOLVED 로 정규화하므로). 그래서 "마지막 non-blank" ≡
     * "무조건 마지막 조각"이 되고, 해결 선언이 중간 조각에 있고 <b>마지막 조각이 검증 로그·후속 잡담뿐</b>이면 그 조각의 기본값 UNRESOLVED 가 앞의
     * RESOLVED 를 덮어쓴다 — 고치려던 모순("해결책은 적혀 있는데 상태는 미해결")이 거울상으로 재현됐다.
     *
     * <p>위치(첫/마지막)로는 결말을 알 수 없다. 알 수 있는 건 <b>어느 조각이 결말을 말했는가</b>이고, 그 판단에는 유형별 필드(해결책·시도 판정)를 봐야
     * 한다. 그래서 판단 자체를 유형에 넘긴다 — 공유 병합기는 여전히 필드 의미를 모른다.
     *
     * @param merged 공유 규칙으로 병합된 카드. LLM 병합·결정론 병합 <b>양쪽</b>이 이 훅을 거친다(프롬프트를 준수한 LLM 도 같은 오답을 낼 수
     *     있어서)
     * @param partials 조각 순서대로의 부분 카드 — 결말이 어디에 있는지 판단할 근거
     */
    default MemoryCard reconcileMerged(MemoryCard merged, List<MemoryCard> partials) {
        return merged;
    }
}
