package com.recall.common.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static String freshKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return Base64.getEncoder().encodeToString(kg.generateKey().getEncoded());
    }

    @Test
    @DisplayName("암호화→복호화 왕복이 원문을 복원한다")
    void roundtrip() throws Exception {
        SecretCipher cipher = new SecretCipher(freshKey());
        String secret = "sk-ant-super-secret";
        String enc = cipher.encrypt(secret);
        assertNotEquals(secret, enc);
        assertEquals(secret, cipher.decrypt(enc));
    }

    @Test
    @DisplayName("같은 원문도 매번 다른 암호문(랜덤 IV)")
    void randomizedIv() throws Exception {
        SecretCipher cipher = new SecretCipher(freshKey());
        assertNotEquals(cipher.encrypt("x"), cipher.encrypt("x"));
    }

    @Test
    @DisplayName("마스터키 없으면 비활성 + 암호화 시도 시 예외(fail-closed)")
    void failClosed() {
        SecretCipher cipher = new SecretCipher("  ");
        assertFalse(cipher.isEnabled());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("x"));
    }
}
