package com.recall.common.secret;

import com.recall.common.exception.SecretKeyUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * provider 키의 at-rest 암호화(AES-256-GCM). 마스터키(env RECALL_SECRET_KEY, base64)가 없으면 비활성 상태로 두고, 암·복호화
 * 시도 시 예외로 드러낸다(조용한 실패 금지 — 평문 저장으로 흐르지 않게).
 */
public final class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
        } else {
            this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key.trim()), "AES");
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        require();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("키 암호화 실패", e);
        }
    }

    public String decrypt(String encoded) {
        require();
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("키 복호화 실패", e);
        }
    }

    private void require() {
        if (key == null) {
            // 서버 미구성(마스터키 없음) — 저장·복호화 양쪽에서 예측 가능한 운영 상황이라 503 전용 코드로.
            throw new SecretKeyUnavailableException(
                    "RECALL_SECRET_KEY 미설정 — 키를 DB에 저장/복호화할 수 없다(fail-closed)");
        }
    }
}
