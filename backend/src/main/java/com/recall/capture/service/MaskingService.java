package com.recall.capture.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * M0 마스킹 — 원문이 저장·외부 LLM·인덱스·로그로 나가기 <b>전에</b> 민감정보를 가린다(불변 원칙: 마스킹 우선). 결정론 단계(정규식 패턴)라 LLM을 쓰지
 * 않는다 — 같은 입력=같은 출력, 멱등(mask∘mask = mask).
 *
 * <p>가리는 값은 <b>버린다</b>(복원용 원문을 남기지 않음 = fail-safe). {@code masked_spans}에는 어디를·무엇을 가렸는지 {@code
 * {start,end,type}}만 기록한다(UI 하이라이트·검토용). 범위(핵심):
 * API키·토큰(OpenAI·Anthropic·GitHub·AWS·Google·Slack·JWT·Bearer·PEM), {@code KEY=VALUE} 시크릿, 이메일. 🔴
 * 릴리스 차단 게이트: 시크릿 잔존 0(PRD §Eval).
 */
@Service
public class MaskingService {

    /** 마스킹 결과: 가려진 텍스트 + 어디를 가렸는지(JSON {@code [{start,end,type}]}). */
    public record MaskResult(String maskedText, String maskedSpansJson) {}

    /** 결정론 치환 규칙(순서 = 우선순위: 구체·고위험 먼저, KEY=VALUE·이메일은 뒤). */
    private record Rule(Pattern pattern, String replacement) {}

    // 치환 표기는 값 대신 종류만 남긴다. 플레이스홀더 문자 ⟨⟩ 는 어떤 패턴에도 매칭되지 않아 재마스킹을 막는다(멱등).
    private static final List<Rule> RULES =
            List.of(
                    new Rule(
                            Pattern.compile(
                                    "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END"
                                            + " [A-Z0-9 ]*PRIVATE KEY-----"),
                            "⟨PRIVATE_KEY⟩"),
                    new Rule(
                            Pattern.compile(
                                    "eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
                            "⟨JWT⟩"),
                    new Rule(Pattern.compile("sk-ant-[A-Za-z0-9_-]{16,}"), "⟨API_KEY⟩"),
                    new Rule(Pattern.compile("sk-proj-[A-Za-z0-9_-]{16,}"), "⟨API_KEY⟩"),
                    new Rule(Pattern.compile("sk-[A-Za-z0-9]{16,}"), "⟨API_KEY⟩"),
                    new Rule(Pattern.compile("AIza[0-9A-Za-z_-]{35,}"), "⟨API_KEY⟩"),
                    new Rule(
                            Pattern.compile(
                                    "gh[posru]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"),
                            "⟨TOKEN⟩"),
                    new Rule(Pattern.compile("AKIA[0-9A-Z]{16}"), "⟨AWS_KEY⟩"),
                    new Rule(Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"), "⟨TOKEN⟩"),
                    new Rule(Pattern.compile("(?i)(Bearer)\\s+[A-Za-z0-9._-]{16,}"), "$1 ⟨TOKEN⟩"),
                    // KEY=VALUE: 값만 가리고 키 이름은 남긴다. 값 문자군에서 ⟨⟩ 제외 → 이미 마스킹된 값은 다시 건드리지 않음.
                    new Rule(
                            Pattern.compile(
                                    "(?i)([A-Za-z0-9_.-]*(?:api[_-]?key|secret|token|password|passwd|pwd|access[_-]?key|client[_-]?secret|apikey)[A-Za-z0-9_.-]*)(\\s*[:=]\\s*\"?)([^\\s\"'⟨⟩]+)"),
                            "$1$2⟨SECRET⟩"),
                    new Rule(
                            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                            "⟨email⟩"));

    private static final Pattern PLACEHOLDER = Pattern.compile("⟨([A-Za-z_]+)⟩");

    public MaskResult mask(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return new MaskResult(rawText, "[]");
        }
        String masked = rawText;
        for (Rule rule : RULES) {
            masked = rule.pattern().matcher(masked).replaceAll(rule.replacement());
        }
        return new MaskResult(masked, spansJson(masked));
    }

    /** 마스킹된 텍스트에서 플레이스홀더 위치를 스캔해 {@code [{start,end,type}]} 를 만든다(원문 값은 담지 않는다). */
    private static String spansJson(String masked) {
        Matcher m = PLACEHOLDER.matcher(masked);
        List<String> spans = new ArrayList<>();
        while (m.find()) {
            spans.add(
                    "{\"start\":"
                            + m.start()
                            + ",\"end\":"
                            + m.end()
                            + ",\"type\":\""
                            + m.group(1)
                            + "\"}");
        }
        return "[" + String.join(",", spans) + "]";
    }
}
