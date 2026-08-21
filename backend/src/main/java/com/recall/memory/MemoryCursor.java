package com.recall.memory;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 키셋 페이지네이션 커서 — 마지막으로 본 아이템의 정렬 키 {@code (createdAt, id)} 를 불투명 문자열로 담는다.
 *
 * <p>인코딩은 {@code base64url("{createdAt ISO-8601}|{id}")}. createdAt 을 밀리초로 절단하면 타이브레이커 경계에서 어긋날 수
 * 있어 ISO-8601 전체 정밀도로 왕복시킨다(무손실). 클라이언트는 내용을 해석하지 않고 그대로 되돌려준다.
 */
record MemoryCursor(OffsetDateTime createdAt, long id) {

    private static final char SEP = '|';

    String encode() {
        String raw = createdAt.toString() + SEP + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 커서 문자열을 디코드한다. 형식이 깨졌으면 {@link IllegalArgumentException} — 호출부는 이를 400 으로 변환한다(조용히 첫 페이지로
     * 폴백하지 않는다 = 조용한 실패 금지).
     */
    static MemoryCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf(SEP);
            if (sep < 0) {
                throw new IllegalArgumentException("커서 구분자 없음");
            }
            OffsetDateTime createdAt = OffsetDateTime.parse(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            return new MemoryCursor(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("잘못된 커서: " + cursor, e);
        }
    }
}
