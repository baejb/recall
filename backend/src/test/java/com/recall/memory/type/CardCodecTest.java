package com.recall.memory.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.recall.common.type.MemoryType;
import com.recall.llm.UserAiContext;
import com.recall.memory.type.knowledge.KnowledgeCard;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link CardCodec} 의 격하 경계 — <b>어떤 실패까지 {@code null} 로 삼키는가</b>.
 *
 * <p>이 구분이 무너지면 재색인이 "손상된 데이터"라며 임베딩을 지우고도 잡을 성공으로 마친다. 그래서 경계를 테스트로 못 박는다.
 */
@Tag("unit")
class CardCodecTest {

    /** knowledge 만 등록된 코덱 — troubleshooting 은 전략이 없는 상태를 재현한다. */
    private static CardCodec codecWithOnlyKnowledge() {
        return new CardCodec(List.of(new KnowledgeOnly()));
    }

    @Test
    @DisplayName("값 모양이 어긋난 카드 한 건은 readOrNull 이 null 로 격하한다(건너뛸 수 있다)")
    void malformedCardDegradesToNull() {
        // facts 는 리스트인데 문자열로 저장된 레거시 행 — 이 한 건 때문에 조회·재색인이 죽어선 안 된다.
        String json = "{\"title\":\"T\",\"facts\":\"리스트여야 하는데 문자열\"}";

        assertNull(codecWithOnlyKnowledge().readOrNull(MemoryType.KNOWLEDGE, json));
    }

    @Test
    @DisplayName("🟠 전략이 없는 유형은 readOrNull 도 격하하지 않는다 — 배선 결함은 드러나야 한다")
    void missingStrategyIsNotDegraded() {
        // 전에는 read() 가 전략 조회까지 try 안에 넣어 이 실패를 "파싱 실패"로 감쌌고, readOrNull 이
        // RuntimeException 을 잡아 null 로 만들었다. 그러면 "이 행의 JSON이 깨졌다"와 "이 유형에 전략이
        // 등록되지 않았다"가 같은 신호가 되고, 후자는 그 유형의 <b>모든</b> 카드에 해당하므로
        // 재색인이 그 유형 임베딩을 전부 지우고도 READY 로 마친다.
        CardCodec codec = codecWithOnlyKnowledge();
        String validJson = "{\"title\":\"T\"}";

        assertThrows(
                IllegalArgumentException.class,
                () -> codec.readOrNull(MemoryType.TROUBLESHOOTING, validJson));
    }

    @Test
    @DisplayName("정상 카드는 유형 스키마의 정규화를 거쳐 돌아온다")
    void readNormalizesThroughCardSchema() {
        KnowledgeCard card =
                (KnowledgeCard)
                        codecWithOnlyKnowledge().read(MemoryType.KNOWLEDGE, "{\"title\":\"T\"}");

        assertEquals("T", card.title());
        assertEquals(List.of(), card.facts(), "null 리스트는 카드 생성자가 빈 리스트로 정규화한다");
    }

    /** 테스트용 최소 전략 — 코덱이 쓰는 것은 {@link #cardType()} 과 {@link #supports()} 뿐이다. */
    private static final class KnowledgeOnly implements ExtractionStrategy {

        @Override
        public MemoryType supports() {
            return MemoryType.KNOWLEDGE;
        }

        @Override
        public Class<? extends MemoryCard> cardType() {
            return KnowledgeCard.class;
        }

        @Override
        public MemoryCard extract(String maskedText, UserAiContext ctx) {
            throw new UnsupportedOperationException("이 테스트는 추출을 호출하지 않는다");
        }
    }
}
