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

    /**
     * 키 이름을 이루는 문자. <b>한글을 포함한다</b> — 이 문자군에 한글이 없으면 한글 키는 접두만 걸리고 접미가 통과한다({@code 토큰값: …}·{@code
     * 암호키=…} 가 그대로 나갔다).
     */
    private static final String KEY_CHARS = "[A-Za-z0-9_.가-힣-]";

    /** 값을 이루는 문자. {@code ⟨⟩} 를 빼서 이미 마스킹된 값을 다시 건드리지 않는다(멱등). */
    private static final String VALUE_CHARS = "[^\\s\"'⟨⟩]";

    /** 키와 값을 가르는 구분자. 값만 가리고 키 이름은 남기려고 따로 캡처한다. */
    private static final String DELIMITER = "(\\s*[:=]\\s*\"?)";

    /**
     * 모호한 키의 값을 "시크릿 모양"으로 인정하는 최소 길이.
     *
     * <p>근거: 이 자리에서 실제로 부딪히는 도메인 값은 짧다({@code 4096} · {@code AES-256-GCM} = 11자). 자격증명은 대체로 그보다 길다.
     * 12 는 그 사이를 가르는 값이며 라벨셋({@code masking-m0.json} 의 음성 케이스)이 이 경계를 고정한다.
     */
    private static final int SECRET_LIKE_MIN_LENGTH = 12;

    /**
     * <b>확정 키</b> — 이 이름이 붙은 값은 도메인 값일 수 없으므로 값이 무엇이든 가린다({@code POSTGRES_PASSWORD=12345678} 처럼
     * 숫자만인 비밀번호도 포함).
     *
     * <p>{@code pass} 는 키의 <b>마지막 세그먼트</b>일 때만 인정한다 — {@code db_pass=} 는 잡고 {@code bypass=}(앞이 글자)
     * · {@code pass_rate=}(뒤가 세그먼트 구분자)는 잡지 않는다. 앞선 {@code pass(?![a-z])} 는 거르는 방향이 뒤집혀 있어서, 막아야 할
     * {@code passphrase=} 를 통과시키고 막지 말아야 할 {@code bypass=true} 를 가렸다. 그래서 {@code passphrase} 는 이름으로
     * 명시한다.
     *
     * <p>{@code 암호키}·{@code 비밀키} 가 {@code 암호}(모호)와 달리 여기 있는 이유: {@code 키} 가 붙으면 더 이상 "암호화 방식"을 가리키는
     * 도메인 단어가 아니라 자격증명이다. 모호한 쪽에 두면 {@code 암호키=hunter2} 처럼 <b>짧은 값이 길이 가드에 걸러져 그대로 나간다</b>.
     */
    private static final String CERTAIN_KEY_NAMES =
            "api[_-]?key|apikey|passphrase|password|passwd|pwd|secret"
                    + "|access[_-]?key|client[_-]?secret|비밀번호|암호키|비밀키"
                    + "|(?<![A-Za-z0-9])pass(?![A-Za-z0-9_.가-힣-])";

    /**
     * <b>모호한 키</b> — 이 제품에서 시크릿 이름 이전에 <b>도메인 단어</b>다. {@code 최대 토큰: 4096} · {@code
     * max_tokens=4096} · {@code 암호: AES-256-GCM} 이 전부 여기 걸린다.
     *
     * <p>가린 값은 버리는 설계(복원용 원문 없음)라 거짓 양성이 곧 <b>되돌릴 수 없는 값 손실</b>이다 — 검토 화면에서 시크릿이 아니라고 판단해도 숫자가 빠진 채
     * memory 로 남는다. 그래서 이 키들은 값이 {@link #SECRET_LIKE_MIN_LENGTH} 이상이고 글자를 포함할 때만 가린다(순수 숫자는 개수·한도이지
     * 자격증명이 아니다).
     */
    private static final String AMBIGUOUS_KEY_NAMES = "token|토큰|암호";

    /**
     * {@code (키)(구분자)} 까지의 패턴. 한글 단어 하나를 공백으로 이어 붙인 형태({@code 비밀번호 확인:})까지 키로 본다 — 공백을 무제한 허용하면 문장이
     * 통째로 키가 되어 과잉 마스킹이 된다.
     */
    private static String keyAndDelimiter(String keyNames) {
        return "("
                + KEY_CHARS
                + "*(?:"
                + keyNames
                + ")"
                + KEY_CHARS
                + "*(?:\\s[가-힣]{1,4})?)"
                + DELIMITER;
    }

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
                    // KEY=VALUE: 값만 가리고 키 이름은 남긴다. 키 이름을 두 단으로 나누는 이유는
                    // CERTAIN_KEY_NAMES·AMBIGUOUS_KEY_NAMES javadoc 에 있다(도메인 단어를 겸하는 키의 값 손실).
                    //
                    // 한국어 키 이름을 넣는 이유: 이 제품의 원문은 한국어 대화다. 영어 키 이름만 보면
                    // "비밀번호: hunter2" 가 그대로 외부 LLM·인덱스로 나간다(🔴 유출).
                    new Rule(
                            Pattern.compile(
                                    "(?i)"
                                            + keyAndDelimiter(CERTAIN_KEY_NAMES)
                                            + "("
                                            + VALUE_CHARS
                                            + "+)"),
                            "$1$2⟨SECRET⟩"),
                    new Rule(
                            Pattern.compile(
                                    "(?i)"
                                            + keyAndDelimiter(AMBIGUOUS_KEY_NAMES)
                                            + "((?="
                                            + VALUE_CHARS
                                            + "*[A-Za-z])"
                                            + VALUE_CHARS
                                            + "{"
                                            + SECRET_LIKE_MIN_LENGTH
                                            + ",})"),
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
