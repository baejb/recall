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
 * API키·토큰(OpenAI·Anthropic·GitHub·AWS·Google·Slack·JWT·Bearer·PEM), {@code KEY=VALUE} 시크릿(한국어 키 이름
 * 포함), 이메일. 🔴 릴리스 차단 게이트: 시크릿 잔존 0(PRD §Eval).
 *
 * <p>커버 범위는 라벨셋으로 고정한다 — {@code src/test/resources/eval/masking-m0.json} 의 케이스가 게이트이고, 아직 못 가리는 모양은
 * {@code masking-gaps.json} 에 케이스로 남아 있다(왜 남겼는지는 {@code docs/eval.md}). 패턴을 고칠 때는 케이스를 옮기는 것으로 범위
 * 변화를 드러낸다.
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
                    //
                    // 키 이름 목록에 한국어(비밀번호·암호·토큰)를 넣는 이유: 이 제품의 원문은 한국어 대화다.
                    // 영어 키 이름만 보면 "비밀번호: hunter2" 가 그대로 외부 LLM·인덱스로 나간다(🔴 유출).
                    // `pass` 는 `db_pass=` 같은 축약 키를 잡되 `(?![a-z])` 로 passing/passed 를 배제한다 —
                    // 그게 없으면 `passing=true` 의 값까지 가려 원문이 읽을 수 없게 된다(거짓 양성).
                    // separator 의 여는 따옴표는 `["']?` — 큰따옴표만 소비하면 `password='hunter2'` 처럼
                    // 작은따옴표로 감싼 값(YAML·Python/Ruby dict 등)이 매칭에서 통째로 빠져 그대로 유출된다.
                    new Rule(
                            Pattern.compile(
                                    "(?i)([A-Za-z0-9_.-]*(?:api[_-]?key|secret|token|password|passwd|pwd|pass(?![a-z])|access[_-]?key|client[_-]?secret|apikey|비밀번호|암호|토큰)[A-Za-z0-9_.-]*)(\\s*[:=]\\s*[\"']?)([^\\s\"'⟨⟩]+)"),
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
