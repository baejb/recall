package com.recall.testsupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.recall.llm.LlmClient;
import com.recall.llm.LlmClientFactory;
import com.recall.llm.LlmProperties;
import com.recall.llm.StubEmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.llm.provider.anthropic.AnthropicChatProvider;
import com.recall.llm.provider.google.GoogleChatProvider;
import com.recall.llm.provider.openai.OpenAiChatProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 품질 Eval(🔵)용 컨텍스트 — 실제 provider 를 호출한다.
 *
 * <p><b>키가 없으면 실패가 아니라 스킵</b>({@link org.junit.jupiter.api.Assumptions}) 이다. 이유: 이 Eval 은 사람이 필요할 때
 * 돌리는 것이고(프롬프트를 고친 직후), 키가 없는 환경에서 빨개지면 "진짜 회귀"와 "안 돌았음"을 구분할 수 없게 된다. 스킵은 리포트에 "안 돌았음"으로 남아 그 구분이
 * 유지된다.
 *
 * <p>DB 를 쓰지 않는다 — 단계 품질만 보므로 Spring 컨텍스트도 띄우지 않고 provider 서술자로 클라이언트를 직접 만든다. embedding 은 이 Eval 의
 * 대상이 아니라 stub 을 넣는다(임베딩 품질은 R+W Recall@k 로 따로 봐야 한다 — {@code docs/eval.md} 범위 밖 참조).
 */
public final class LlmEvalSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmEvalSupport.class);

    private LlmEvalSupport() {}

    /**
     * env 로 설정된 provider 로 chat 컨텍스트를 만든다. 키가 없으면 테스트를 스킵한다.
     *
     * <p>읽는 값: {@code RECALL_LLM_PROVIDER}(기본 anthropic) · {@code RECALL_LLM_API_KEY} · {@code
     * RECALL_LLM_MODEL_STRONG}(비면 provider 기본 모델). 운영 설정과 같은 이름을 쓰는 이유는 {@code .env} 하나로 앱과 Eval 을
     * 같은 모델에 맞추기 위해서다 — 다른 모델로 채점하면 그 점수는 운영 품질을 말하지 않는다.
     */
    public static UserAiContext chatContext() {
        String apiKey = env("RECALL_LLM_API_KEY");
        assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "RECALL_LLM_API_KEY 가 없어 LLM Eval 을 건너뛴다 (실행: ./gradlew llmEval)");

        String provider = orDefault(env("RECALL_LLM_PROVIDER"), "anthropic");
        String model = env("RECALL_LLM_MODEL_STRONG");
        LlmClientFactory factory =
                new LlmClientFactory(
                        List.of(
                                new AnthropicChatProvider(),
                                new OpenAiChatProvider(),
                                new GoogleChatProvider()));
        LlmClient llm =
                factory.forSettings(
                        new LlmProperties(
                                provider, apiKey, model, env("RECALL_LLM_BASE_URL"), null));

        return new UserAiContext(1L, llm, new StubEmbeddingClient(), true, false);
    }

    /**
     * 라벨셋 정확도를 임계와 비교해 판정한다(PRD §7.2 "허용 범위 판정").
     *
     * <p>단건 단정이 아니라 비율로 보는 이유: 확률적 단계는 케이스 하나가 틀리는 것 자체가 결함이 아니다. 임계 미달일 때만 실패로 보고, 어느 케이스가 틀렸는지는
     * 메시지에 남겨 프롬프트를 고칠 단서를 준다.
     *
     * @param stage 단계 이름(메시지용)
     * @param total 전체 케이스 수
     * @param misses 틀린 케이스 설명(비면 만점)
     * @param threshold 통과 정확도(0~1)
     */
    public static void assertAccuracy(
            String stage, int total, List<String> misses, double threshold) {
        double accuracy = total == 0 ? 0 : (double) (total - misses.size()) / total;
        if (accuracy < threshold) {
            throw new AssertionError(
                    String.format(
                            "%s 정확도 %.2f < 임계 %.2f (%d/%d)%n틀린 케이스:%n  - %s",
                            stage,
                            accuracy,
                            threshold,
                            total - misses.size(),
                            total,
                            String.join("\n  - ", misses)));
        }
        log.info(
                "{} 정확도 {} ({}/{}) — 임계 {} 통과",
                stage,
                String.format("%.2f", accuracy),
                total - misses.size(),
                total,
                threshold);
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
