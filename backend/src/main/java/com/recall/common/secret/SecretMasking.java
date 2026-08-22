package com.recall.common.secret;

import java.util.regex.Pattern;

/**
 * 로그·예외 메시지에 provider API 키가 실리는 것을 막는 방어적 마스킹(defense-in-depth). 마스킹 우선 원칙과 별도로 얹는 자격증명 보호용 — URL
 * 쿼리(`?key=...`)나 원문에 섞여 나오는 키 형태를 정규식으로 찾아 지운다.
 */
public final class SecretMasking {

    private static final String REDACTED = "***";

    // OpenAI/Anthropic 계열: sk-XXXXXXXX...
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    // Google(Gemini) 계열: AIzaXXXXXXXX...
    private static final Pattern GOOGLE_KEY = Pattern.compile("AIza[A-Za-z0-9_-]{8,}");

    // URL 쿼리 파라미터로 실린 키: ?key=... 또는 &key=... — key= 접두는 남기고 값만 지운다.
    private static final Pattern URL_KEY_PARAM = Pattern.compile("([?&]key=)[^&\\s\"]+");

    private SecretMasking() {}

    /** 주어진 문자열에서 알려진 키 패턴을 찾아 {@code ***}로 치환한다. null은 그대로 null. */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = text;
        masked = URL_KEY_PARAM.matcher(masked).replaceAll("$1" + REDACTED);
        masked = SK_KEY.matcher(masked).replaceAll(REDACTED);
        masked = GOOGLE_KEY.matcher(masked).replaceAll(REDACTED);
        return masked;
    }
}
