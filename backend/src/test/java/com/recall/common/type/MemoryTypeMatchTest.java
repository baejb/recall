package com.recall.common.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 저장 경로(유형 라우팅)와 조회 경로(분류 C)가 공유하는 유형 이름 매칭 규칙. 확률적인 LLM 호출 바깥의 <b>결정론</b>이라 여기서 못 박는다. */
class MemoryTypeMatchTest {

    private static final Set<MemoryType> BOTH =
            Set.of(MemoryType.KNOWLEDGE, MemoryType.TROUBLESHOOTING);

    @Test
    @DisplayName("이름이 정확히 하나 등장하면 그 유형 — 대소문자·주변 산문은 허용")
    void matchesSingleName() {
        assertEquals(
                MemoryType.TROUBLESHOOTING, MemoryTypeMatch.exactlyOne("TROUBLESHOOTING", BOTH));
        assertEquals(MemoryType.KNOWLEDGE, MemoryTypeMatch.exactlyOne("knowledge", BOTH));
        assertEquals(
                MemoryType.TROUBLESHOOTING,
                MemoryTypeMatch.exactlyOne("이 메모는 troubleshooting 입니다.", BOTH));
    }

    @Test
    @DisplayName("두 유형 이름이 함께 등장하면 모호 → null. 부정문의 앞 이름에 걸려 오분류되지 않는다")
    void rejectsAmbiguousOutput() {
        // 이전 규칙("이름이 등장하는 첫 지원 유형", 열거 순서대로)은 KNOWLEDGE 가 먼저 선언돼 있어 이 출력을
        // KNOWLEDGE 로 읽었고, 매치가 있었으니 격하 로그도 남지 않았다.
        assertNull(MemoryTypeMatch.exactlyOne("KNOWLEDGE 가 아니라 TROUBLESHOOTING 입니다", BOTH));
        assertNull(MemoryTypeMatch.exactlyOne("TROUBLESHOOTING? 아니면 KNOWLEDGE?", BOTH));
    }

    @Test
    @DisplayName("지원 목록에 없는 유형 이름·잡소리·null 은 null(격하는 호출부 책임)")
    void returnsNullWhenUndecidable() {
        assertNull(MemoryTypeMatch.exactlyOne("COMMAND_CODE", BOTH));
        assertNull(MemoryTypeMatch.exactlyOne("글쎄요", BOTH));
        assertNull(MemoryTypeMatch.exactlyOne(null, BOTH));
        // 지원 목록에 없으면 이름이 맞아도 고르지 않는다 — 전략 없는 유형으로 라우팅하면 파이프라인이 터진다.
        assertNull(
                MemoryTypeMatch.exactlyOne("TROUBLESHOOTING", Set.of(MemoryType.KNOWLEDGE)),
                "지원 유형 밖은 매치되지 않는다");
    }
}
