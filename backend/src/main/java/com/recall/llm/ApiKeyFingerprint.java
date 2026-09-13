package com.recall.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * API 키의 충돌 저항 지문 — 클라이언트 캐시 키 구성용. 32비트 {@code String.hashCode()} 는 충돌 확률이 무시할 수 없어, 서로 다른 두 사용자의
 * 키가 같은 캐시 키로 접히면 {@code computeIfAbsent} 가 먼저 만들어진 <b>다른 사용자의 클라이언트</b>를 반환해 그 사용자의 키로 호출이
 * 나간다(자격증명 교차사용, 🔴 영향). SHA-256 전체 다이제스트로 이 충돌 여지를 없앤다. 키 값 자체는 남기지 않는다(지문만).
 */
final class ApiKeyFingerprint {

    private ApiKeyFingerprint() {}

    static String of(String apiKey) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원(JDK 표준 — 도달 불가)", e);
        }
    }
}
