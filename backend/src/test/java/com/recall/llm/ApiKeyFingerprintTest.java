package com.recall.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 클라이언트 캐시 키가 32비트 {@code String.hashCode()} 였을 때의 충돌(서로 다른 사용자의 키가 같은 캐시 키로 접혀 다른 사용자의 클라이언트를 반환)을
 * SHA-256 지문이 막는지 고정한다.
 */
class ApiKeyFingerprintTest {

    @Test
    @DisplayName("hashCode 충돌쌍('Aa'·'BB')도 서로 다른 지문을 낸다 — 캐시 오사용 방지")
    void distinguishesHashCodeCollisions() {
        // "Aa".hashCode() == "BB".hashCode() == 2112 (자바 고전 충돌쌍). 옛 캐시 키는 이 둘을 같게 봤다.
        assertEquals("Aa".hashCode(), "BB".hashCode(), "전제: 두 키의 hashCode 는 충돌한다");
        assertNotEquals(
                ApiKeyFingerprint.of("Aa"),
                ApiKeyFingerprint.of("BB"),
                "충돌쌍이라도 지문은 달라야 한다(다른 키 = 다른 클라이언트)");
    }

    @Test
    @DisplayName("같은 키는 같은 지문(캐시 재사용은 유지)")
    void sameKeySameFingerprint() {
        assertEquals(ApiKeyFingerprint.of("sk-test-1234"), ApiKeyFingerprint.of("sk-test-1234"));
        assertNotEquals(ApiKeyFingerprint.of("sk-test-1234"), ApiKeyFingerprint.of("sk-test-9999"));
    }
}
