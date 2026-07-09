package com.recall.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Pattern-rule masking of secrets (API keys, tokens, passwords), applied BEFORE any
 * external LLM send. This is a non-negotiable rule in the design docs.
 *
 * <p>The original text is preserved elsewhere; here we only produce the masked text and
 * the span list so the user can later review/restore false positives.
 */
@Service
public class MaskingService {

    public record Span(int start, int end, String type) {
    }

    public record MaskResult(String masked, List<Span> spans) {
    }

    private record Rule(String type, Pattern pattern) {
    }

    // Intentionally conservative starter rules. TODO: expand + make configurable.
    private static final List<Rule> RULES = List.of(
            new Rule("API_KEY", Pattern.compile("\\b(?:sk|pk)-[A-Za-z0-9]{8,}\\b")),
            new Rule("BEARER_TOKEN", Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._-]{10,}")),
            new Rule("AWS_ACCESS_KEY", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new Rule("PASSWORD_ASSIGN", Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:]\\s*\\S+"))
    );

    public MaskResult mask(String input) {
        String masked = input;
        List<Span> spans = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(masked);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String replacement = "[MASKED_" + rule.type() + "]";
                spans.add(new Span(m.start(), m.start() + replacement.length(), rule.type()));
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            masked = sb.toString();
        }
        return new MaskResult(masked, spans);
    }
}
