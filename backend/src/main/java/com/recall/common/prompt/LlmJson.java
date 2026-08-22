package com.recall.common.prompt;

/**
 * LLM 응답 텍스트에서 JSON 본문만 건져내는 순수 유틸.
 *
 * <p>모델은 structured output을 지시해도 산문·마크다운 코드펜스로 감싸 오는 경우가 있어, 첫 <code>{</code>부터 마지막 <code>}</code>
 * 까지를 JSON 후보로 본다. 여러 단계(S2 추출·S4 판정)가 같은 방어를 필요로 하므로 한 곳에 둔다 — 유형이 늘어날 때마다 같은 코드를 복제하지 않기 위해.
 *
 * <p>결정론 유틸이라 부작용·로깅이 없다. "JSON을 못 찾았다"는 사실은 {@code null}로 돌려주고, 그걸 어떻게 드러낼지(로그·fallback)는 호출부가
 * 정한다(조용한 실패 금지는 호출부 책임).
 */
public final class LlmJson {

    private LlmJson() {}

    /**
     * 응답 텍스트에서 JSON 객체 구간만 잘라 반환한다.
     *
     * @param raw LLM 응답 원문(null 허용)
     * @return JSON 객체 문자열, 찾지 못하면 null
     */
    public static String extractObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }
}
