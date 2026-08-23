package com.recall.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 키셋 커서 인코드/디코드 왕복 — 타이브레이커 경계가 어긋나지 않게 정밀도를 보존하는지 본다. */
class MemoryCursorTest {

    @Test
    @DisplayName("인코드→디코드 왕복이 createdAt(마이크로초)·id 를 무손실 보존한다")
    void roundTripPreservesPrecision() {
        OffsetDateTime ts = OffsetDateTime.of(2026, 8, 14, 10, 0, 0, 123_456_000, ZoneOffset.UTC);
        MemoryCursor original = new MemoryCursor(ts, 4242L);

        MemoryCursor decoded = MemoryCursor.decode(original.encode());

        assertEquals(original.createdAt(), decoded.createdAt());
        assertEquals(original.id(), decoded.id());
    }

    @Test
    @DisplayName("깨진 커서는 IllegalArgumentException")
    void rejectsMalformedCursor() {
        assertThrows(IllegalArgumentException.class, () -> MemoryCursor.decode("!!!not-base64!!!"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MemoryCursor.decode(
                                java.util.Base64.getUrlEncoder()
                                        .encodeToString("구분자없음".getBytes())));
    }
}
