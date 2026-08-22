package com.recall.capture.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recall.capture.service.MaskingService.MaskResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M0 마스킹(결정론 패턴) 단위 테스트. 순수 함수(같은 입력=같은 출력)라 Spring 컨텍스트 없이 검증한다.
 *
 * <p>🔴 릴리스 차단 게이트: 시크릿 표본에서 <b>민감패턴 잔존 0</b>(PRD §Eval). 마스킹 전 원문이 이후 단계로 새면 치명.
 */
class MaskingServiceTest {

    private final MaskingService masking = new MaskingService();

    private String mask(String raw) {
        return masking.mask(raw).maskedText();
    }

    // ── 키/토큰 ──────────────────────────────────────────────

    @Test
    @DisplayName("OpenAI sk-proj / sk- 키를 가린다")
    void masksOpenAiKeys() {
        String proj = mask("env OPENAI=sk-proj-9fQ2v7bXeR1aBcDeFgHiJkLmNoPqRsTuVwXyZ01 done");
        assertFalse(proj.contains("sk-proj-9fQ2v7bXeR1aBcDeFgHiJkLmNoPqRsTuVwXyZ01"));
        assertTrue(proj.contains("⟨API_KEY⟩"));

        String legacy = mask("key sk-abcdEFGH1234567890ijklMNOP tail");
        assertFalse(legacy.contains("sk-abcdEFGH1234567890ijklMNOP"));
        assertTrue(legacy.contains("⟨API_KEY⟩"));
    }

    @Test
    @DisplayName("Anthropic sk-ant- 키를 가린다")
    void masksAnthropicKey() {
        String out = mask("RECALL_LLM_API_KEY=sk-ant-api03-AbCdEf012345678901234567890xyz");
        assertFalse(out.contains("sk-ant-api03-AbCdEf012345678901234567890xyz"));
        assertTrue(out.contains("⟨"));
    }

    @Test
    @DisplayName("GitHub 토큰(ghp_)을 가린다")
    void masksGithubToken() {
        String out = mask("clone with ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 please");
        assertFalse(out.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"));
        assertTrue(out.contains("⟨TOKEN⟩"));
    }

    @Test
    @DisplayName("AWS 액세스 키(AKIA)를 가린다")
    void masksAwsKey() {
        String out = mask("aws_access_key_id AKIAIOSFODNN7EXAMPLE region");
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"));
        assertTrue(out.contains("⟨"));
    }

    @Test
    @DisplayName("JWT를 가린다")
    void masksJwt() {
        String jwt =
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String out = mask("Authorization cookie " + jwt + " ok");
        assertFalse(out.contains(jwt));
        assertTrue(out.contains("⟨JWT⟩"));
    }

    @Test
    @DisplayName("Bearer 토큰을 가리되 'Bearer' 단어는 남긴다")
    void masksBearerKeepsWord() {
        String out = mask("header: Bearer abcDEF1234567890ghiJKL9876543210 end");
        assertFalse(out.contains("abcDEF1234567890ghiJKL9876543210"));
        assertTrue(out.contains("Bearer ⟨TOKEN⟩"));
    }

    @Test
    @DisplayName("PEM 개인키 블록을 가린다")
    void masksPemPrivateKey() {
        String pem =
                "-----BEGIN RSA PRIVATE KEY-----\nMIIBOwIBAAJBAKj34GkxFhD90vcNLYLInFEX\n-----END RSA PRIVATE KEY-----";
        String out = mask("config " + pem + " loaded");
        assertFalse(out.contains("MIIBOwIBAAJBAKj34GkxFhD90vcNLYLInFEX"));
        assertTrue(out.contains("⟨PRIVATE_KEY⟩"));
    }

    // ── KEY=VALUE 시크릿 ─────────────────────────────────────

    @Test
    @DisplayName("KEY=VALUE 시크릿은 값만 가리고 키 이름은 남긴다")
    void masksKeyValueSecretKeepingName() {
        String out = mask("DB_PASSWORD=hunter2xYz9 and client_secret: \"s3cr3tValueHere\"");
        assertFalse(out.contains("hunter2xYz9"));
        assertFalse(out.contains("s3cr3tValueHere"));
        assertTrue(out.contains("DB_PASSWORD="));
        assertTrue(out.contains("client_secret"));
        assertTrue(out.contains("⟨SECRET⟩"));
    }

    // ── 이메일 ───────────────────────────────────────────────

    @Test
    @DisplayName("이메일을 가린다")
    void masksEmail() {
        String out = mask("문의는 hrlee@proten.co.kr 로 주세요");
        assertFalse(out.contains("hrlee@proten.co.kr"));
        assertTrue(out.contains("⟨email⟩"));
    }

    // ── 과마스킹 방지 · 결정성 ────────────────────────────────

    @Test
    @DisplayName("정상 텍스트는 그대로 둔다(과마스킹 방지)")
    void benignTextUnchanged() {
        String benign = "이 코드는 3초 지연 후 5회 재시도한다. status 200 OK, count=42 처리 완료.";
        assertEquals(benign, mask(benign));
    }

    @Test
    @DisplayName("멱등: mask(mask(x)) == mask(x)")
    void idempotent() {
        String raw = "key sk-abcdEFGH1234567890ijklMNOP mail a@b.com PASSWORD=zzz9Value";
        String once = mask(raw);
        String twice = mask(once);
        assertEquals(once, twice);
    }

    // ── 스팬 기록 ────────────────────────────────────────────

    @Test
    @DisplayName("masked_spans에 {start,end,type}를 기록하고, 원문 시크릿은 담지 않는다")
    void recordsSpansWithoutRawSecret() {
        MaskResult r = masking.mask("mail a@b.com key sk-abcdEFGH1234567890ijklMNOP");
        String spans = r.maskedSpansJson();
        assertTrue(spans.contains("\"type\""));
        assertTrue(spans.contains("\"start\""));
        assertTrue(spans.contains("\"end\""));
        assertTrue(spans.contains("email"));
        // fail-safe: 스팬 JSON에 원문 시크릿 값이 남으면 안 된다
        assertFalse(spans.contains("a@b.com"));
        assertFalse(spans.contains("sk-abcdEFGH1234567890ijklMNOP"));
    }

    // ── 🔴 릴리스 차단: 잔존 0 배터리 ─────────────────────────

    @Test
    @Tag("release-gate")
    @DisplayName("🔴 시크릿 배터리 — 마스킹 후 원문 잔존 0")
    void zeroResidueAcrossBattery() {
        List<String> secrets =
                List.of(
                        "sk-proj-9fQ2v7bXeR1aBcDeFgHiJkLmNoPqRsTuVwXyZ01",
                        "sk-ant-api03-AbCdEf012345678901234567890xyz",
                        "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
                        "AKIAIOSFODNN7EXAMPLE",
                        "AIzaSyD-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456",
                        "hrlee@proten.co.kr");
        for (String s : secrets) {
            String out = mask("prefix " + s + " suffix");
            assertFalse(out.contains(s), "잔존한 시크릿: " + s);
        }
    }
}
