package com.recall.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretMaskingTest {

    @Test
    @DisplayName("sk- 형식 키를 지운다")
    void masksSkKey() {
        String masked = SecretMasking.mask("auth failed for key sk-abcdEFGH12345678, retry later");
        assertFalse(masked.contains("sk-abcdEFGH12345678"));
        assertTrue(masked.contains("***"));
    }

    @Test
    @DisplayName("AIza 형식(Google) 키를 지운다")
    void masksGoogleKey() {
        String masked =
                SecretMasking.mask(
                        "request to https://x/y?other=1 with AIzaFAKEKEY123456 embedded");
        assertFalse(masked.contains("AIzaFAKEKEY123456"));
        assertTrue(masked.contains("***"));
    }

    @Test
    @DisplayName("URL 쿼리의 key= 값만 지우고 접두는 남긴다")
    void masksUrlKeyParam() {
        String masked =
                SecretMasking.mask(
                        "GET https://generativelanguage.googleapis.com/v1beta/models/x:generateContent?key=SECRETVALUE123 failed");
        assertFalse(masked.contains("SECRETVALUE123"));
        assertTrue(masked.contains("key=***"));
    }

    @Test
    @DisplayName("키가 없는 평범한 텍스트는 그대로 둔다")
    void leavesNormalTextIntact() {
        String text = "임베딩 설정 검증 실패(키/모델 확인): HTTP 401 Unauthorized";
        assertEquals(text, SecretMasking.mask(text));
    }
}
