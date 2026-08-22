package com.recall.common.type;

import com.recall.common.prompt.LlmJson;
import java.util.Collection;
import java.util.Locale;

/**
 * LLM 분류 출력에서 memory 유형을 읽어내는 결정론 매처.
 *
 * <p>저장 경로(유형 라우팅)와 조회 경로(분류 C)가 <b>같은 판단</b>을 하는데 서로 다른 규칙을 갖고 있었다. 저장 쪽은 {@code
 * MemoryType.name()} 매칭(유형이 늘어도 코드를 안 고침), 조회 쪽은 {@code "TROUBLE"}·{@code "트러블"}·{@code "트슈"} 한국어
 * 키워드 표 + else KNOWLEDGE 였다. 그래서 (1) 같은 문장이 저장 때와 조회 때 다른 유형으로 분류될 수 있었고, (2) 세 번째 유형이 SPI를 갖춰
 * 자가등록돼도 조회 경로는 그 유형을 <b>반환할 수 없어</b> 조용히 KNOWLEDGE 로 검색했다. 규칙을 한 곳에 모아 두 경로가 같은 답을 내게 한다({@link
 * LlmJson}과 같은 선례 — 여러 단계가 같은 방어를 필요로 하면 공유 유틸로 둔다).
 *
 * <p>결정론 유틸이라 부작용·로깅이 없다. "유형을 못 정했다"는 사실은 {@code null}로 돌려주고, 그걸 어떻게 드러낼지(로그·격하)는 호출부가 정한다(조용한 실패
 * 금지는 호출부 책임).
 */
public final class MemoryTypeMatch {

    private MemoryTypeMatch() {}

    /**
     * 출력에 이름이 등장하는 지원 유형이 <b>정확히 하나</b>면 그 유형, 없거나 둘 이상이면 {@code null}.
     *
     * <p>"정확히 하나"인 이유: 이전 규칙은 "이름이 등장하는 <b>첫</b> 지원 유형(열거 순서대로)"이었고 {@code MemoryType}은 KNOWLEDGE 가
     * 먼저 선언돼 있다. 그래서 모델이 {@code "KNOWLEDGE 가 아니라 TROUBLESHOOTING 입니다"}처럼 부정문으로 답하면 <b>부정된 이름에
     * 걸려</b> KNOWLEDGE 로 라우팅되고, 매치가 있었으니 격하 로그도 남지 않았다. 두 이름이 함께 등장하는 출력은 어느 쪽인지 판단할 근거가 없으므로 "모호"로
     * 돌려 호출부가 기본 유형으로 격하하고 <b>로그로 드러내게</b> 한다.
     *
     * <p>대문자화는 {@link Locale#ROOT} 로 한다 — 기본 로케일 {@code toUpperCase()}는 터키어 로케일에서 {@code i → İ} 로
     * 올려 {@code TROUBLESHOOTING} 매칭이 깨진다.
     *
     * @param output LLM 분류 출력(null 허용)
     * @param supported 후보 유형(전략이 등록된 유형만)
     */
    public static MemoryType exactlyOne(String output, Collection<MemoryType> supported) {
        if (output == null) {
            return null;
        }
        String upper = output.toUpperCase(Locale.ROOT);
        MemoryType matched = null;
        for (MemoryType type : supported) {
            if (upper.contains(type.name())) {
                if (matched != null) {
                    return null; // 두 유형 이상이 함께 등장 → 모호
                }
                matched = type;
            }
        }
        return matched;
    }
}
