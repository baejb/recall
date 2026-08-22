package com.recall.memory.type.troubleshooting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.recall.common.prompt.PromptLoader;
import com.recall.memory.type.MemoryCard;
import com.recall.memory.type.troubleshooting.TroubleshootingCard.Attempt;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S3 병합 결과의 결말 보정 — 위치 규칙이 아니라 <b>어느 조각이 결말을 말했는가</b>로 status 를 고른다.
 *
 * <p>이 클래스는 두 번의 오답을 함께 고정한다. ① "첫 non-blank": 앞 조각은 아직 해결 전이라 해결된 대화가 미해결로 저장됐다. ② "마지막
 * non-blank": 조각 추출이 status 를 항상 채우므로(모르는 값 → UNRESOLVED) 결말을 말하지 않은 꼬리 조각의 기본값이 앞의 RESOLVED 를 덮어,
 * 같은 모순이 방향만 바꿔 재현됐다. 두 방향을 각각 테스트로 막는다.
 */
class TroubleshootingReconcileMergedTest {

    private final TroubleshootingExtraction strategy =
            new TroubleshootingExtraction(new PromptLoader());

    private static TroubleshootingCard chunk(
            String status, String finalSolution, Attempt... attempts) {
        return new TroubleshootingCard(
                "컨테이너 OOM",
                "",
                List.of(),
                "죽는다",
                "",
                "",
                "",
                List.of(attempts),
                "",
                finalSolution,
                status);
    }

    @Test
    @DisplayName("결말이 중간 조각에 있고 마지막 조각이 잡담뿐이면 — 중간 조각의 RESOLVED 를 지킨다")
    void trailingSilentChunkDoesNotOverrideEarlierOutcome() {
        // 마지막 조각: 검증 로그·후속 논의뿐 → status 는 추출 기본값 UNRESOLVED, 해결 근거 없음.
        // "마지막 조각을 쓴다"는 규칙이 여기서 정확히 틀렸다.
        List<MemoryCard> partials =
                List.of(
                        chunk(TroubleshootingCard.UNRESOLVED, ""),
                        chunk(TroubleshootingCard.RESOLVED, "limit 4GB 상향"),
                        chunk(TroubleshootingCard.UNRESOLVED, ""));
        // 공유 병합기는 첫 non-blank 를 골라 UNRESOLVED 를 들고 온다.
        TroubleshootingCard merged = chunk(TroubleshootingCard.UNRESOLVED, "limit 4GB 상향");

        TroubleshootingCard out = (TroubleshootingCard) strategy.reconcileMerged(merged, partials);

        assertEquals(TroubleshootingCard.RESOLVED, out.status());
        assertEquals("limit 4GB 상향", out.finalSolution(), "다른 필드는 건드리지 않는다");
    }

    @Test
    @DisplayName("결말이 마지막 조각에 있으면 그 값을 쓴다 — 첫 non-blank 가 앞 조각의 미해결을 물고 오지 않는다")
    void finalChunkOutcomeWins() {
        List<MemoryCard> partials =
                List.of(
                        chunk(TroubleshootingCard.UNRESOLVED, ""),
                        chunk(TroubleshootingCard.RESOLVED, "limit 4GB 상향"));
        TroubleshootingCard merged = chunk(TroubleshootingCard.UNRESOLVED, "limit 4GB 상향");

        TroubleshootingCard out = (TroubleshootingCard) strategy.reconcileMerged(merged, partials);

        assertEquals(TroubleshootingCard.RESOLVED, out.status());
    }

    @Test
    @DisplayName("status 는 기본값이지만 통한 시도가 있으면 그 조각도 결말을 말한 것으로 본다")
    void workedAttemptCountsAsAssertingOutcome() {
        List<MemoryCard> partials =
                List.of(
                        chunk(TroubleshootingCard.PARTIAL, "우회 적용"),
                        chunk(
                                TroubleshootingCard.UNRESOLVED,
                                "",
                                new Attempt("한도 상향", "에러 사라짐", Attempt.WORKED)));
        TroubleshootingCard merged = chunk(TroubleshootingCard.PARTIAL, "우회 적용");

        TroubleshootingCard out = (TroubleshootingCard) strategy.reconcileMerged(merged, partials);

        assertEquals(
                TroubleshootingCard.UNRESOLVED,
                out.status(),
                "통한 시도를 담은 마지막 조각이 결말을 말했으므로 그 판정을 쓴다");
    }

    @Test
    @DisplayName("🟠 뒤 조각이 실패한 시도로 후퇴를 말하면 앞의 RESOLVED 를 뒤집는다")
    void laterFailedAttemptOverridesEarlierResolution() {
        // 세 번째 오답이었다: "통한 시도"만 결말 발화로 봐서 <b>후퇴는 앞의 성공을 뒤집지 못했다</b>.
        // 앞 조각이 RESOLVED + 해결책을 담고, 뒤 조각이 "그걸로 배포했는데 다시 터졌다"(status 는 추출
        // 기본값 UNRESOLVED, failed 시도만 있고 해결책 없음)여도 뒤 조각이 발화로 안 잡혀
        // 고쳤다가 다시 깨진 대화가 RESOLVED 로 저장됐다 — Decision 13이 가장 위험하다고 지목한 방향이다.
        List<MemoryCard> partials =
                List.of(
                        chunk(TroubleshootingCard.RESOLVED, "limit 4GB 상향"),
                        chunk(
                                TroubleshootingCard.UNRESOLVED,
                                "",
                                new Attempt("상향 후 재배포", "다시 OOM", Attempt.FAILED)));
        TroubleshootingCard merged = chunk(TroubleshootingCard.RESOLVED, "limit 4GB 상향");

        TroubleshootingCard out = (TroubleshootingCard) strategy.reconcileMerged(merged, partials);

        assertEquals(
                TroubleshootingCard.UNRESOLVED,
                out.status(),
                "판정된 결과(failed)를 담은 마지막 조각이 결말을 말했으므로 그 판정을 쓴다");
    }

    @Test
    @DisplayName("판정되지 않은 시도(unknown)만 담긴 꼬리 조각은 여전히 결말을 말하지 않는다")
    void unknownOutcomeDoesNotAssert() {
        // judged() 를 넓히면서 UNKNOWN 까지 들어가면 원래 버그가 되살아난다: 결말을 모르는 꼬리 조각의
        // 기본값 UNRESOLVED 가 앞의 RESOLVED 를 다시 덮는다. 그 경계를 여기서 고정한다.
        List<MemoryCard> partials =
                List.of(
                        chunk(TroubleshootingCard.RESOLVED, "limit 4GB 상향"),
                        chunk(
                                TroubleshootingCard.UNRESOLVED,
                                "",
                                new Attempt("로그 재확인", "판단 보류", Attempt.UNKNOWN)));
        TroubleshootingCard merged = chunk(TroubleshootingCard.UNRESOLVED, "limit 4GB 상향");

        TroubleshootingCard out = (TroubleshootingCard) strategy.reconcileMerged(merged, partials);

        assertEquals(TroubleshootingCard.RESOLVED, out.status(), "앞 조각의 판정이 유지된다");
    }

    @Test
    @DisplayName("아무 조각도 결말을 말하지 않으면 병합 결과를 그대로 둔다 — 지어내지 않는다")
    void noAssertionLeavesMergedAsIs() {
        List<MemoryCard> partials =
                List.of(
                        chunk(TroubleshootingCard.UNRESOLVED, ""),
                        chunk(TroubleshootingCard.UNRESOLVED, ""));
        TroubleshootingCard merged = chunk(TroubleshootingCard.UNRESOLVED, "");

        TroubleshootingCard out = (TroubleshootingCard) strategy.reconcileMerged(merged, partials);

        assertEquals(TroubleshootingCard.UNRESOLVED, out.status());
    }
}
