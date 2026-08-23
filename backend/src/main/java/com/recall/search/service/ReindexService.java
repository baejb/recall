package com.recall.search.service;

import com.recall.llm.AiContextFactory;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.UserAiContext;
import com.recall.memory.MemoryAccess;
import com.recall.memory.StoredMemory;
import com.recall.memory.type.CardCodec;
import com.recall.memory.type.MemoryCard;
import com.recall.search.SearchIndex;
import com.recall.settings.EmbeddingModelChangedEvent;
import com.recall.settings.EmbeddingStatus;
import com.recall.settings.SettingsService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 임베딩 모델이 바뀌면 그 사용자의 활성 memory 전체를 새 모델로 재임베딩한다(저장 경로 성격이라 @Async 배경 작업). 재색인은 <b>사용자별</b>이다 —
 * 데이터(재임베딩 대상)도 사용자 스코프, 상태 전이(READY/FAILED)도 사용자 스코프 UPDATE 로만 쓴다(설계 문서 §6). 전역 스윕은 기본 API로 두지
 * 않는다.
 *
 * <p>상태 전이: 설정 변경 트랜잭션이 REINDEXING + 새 세대(generation)를 커밋한 뒤(AFTER_COMMIT) 재색인이 돌고, 완료 시 READY / 실패
 * 시 FAILED 로 전이한다(조용한 실패 금지). BM25 tsvector 는 임베딩 모델과 무관하므로 벡터만 다시 만든다.
 *
 * <p>동시성(P1-c): 재색인 잡은 {@code reindexExecutor}(단일 스레드)로 <b>직렬화</b>되고, 각 잡은 부여받은 세대 토큰을 들고 돈다. 잡 시작
 * 시점에 {@link UserAiContext#requireEmbedding()}으로 임베딩 클라이언트를 <b>하나로 고정(pin)</b>해 모든 문서를 같은 모델로
 * 임베딩하고, 종료 시 행의 현재 세대가 아직 자기 세대와 같을 때만(user_id + generation 조건부 UPDATE) 상태를 쓴다. 이 펜싱으로, 임베딩 설정을
 * 연달아 바꿔도 <b>마지막 잡만</b> READY 를 쓰고 뒤늦은 앞선 잡은 상태 쓰기를 건너뛴다 — 반쯤 재색인된(모델 혼재) 인덱스가 READY 로 보이지 않는다.
 *
 * <p>소유자 신뢰 경계: 재색인 배경 스레드는 요청 스레드의 {@code CurrentUserProvider}(스레드로컬)에 의존하지 않는다 — userId 는 {@link
 * EmbeddingModelChangedEvent#userId()}로 전달받는다(설정 변경 요청 스레드가 currentUser 로 채운 값). {@link
 * AiContextFactory#forUser(long)}는 이 신뢰된 userId 를 그대로 그 사용자 설정 조회에 쓴다.
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final MemoryAccess memories;
    private final SearchIndex searchIndex;
    private final AiContextFactory contextFactory;
    private final SettingsService settings;

    /** 카드 되읽기는 이 코덱만 한다(모듈마다 ObjectMapper 를 두면 되읽기가 유형 스키마를 건너뛴다). */
    private final CardCodec cardCodec;

    public ReindexService(
            MemoryAccess memories,
            SearchIndex searchIndex,
            AiContextFactory contextFactory,
            CardCodec cardCodec,
            SettingsService settings) {
        this.memories = memories;
        this.searchIndex = searchIndex;
        this.contextFactory = contextFactory;
        this.cardCodec = cardCodec;
        this.settings = settings;
    }

    /**
     * 설정 변경(임베딩 모델)이 커밋된 뒤 배경에서 그 사용자의 재색인을 시작한다. AFTER_COMMIT 이라 REINDEXING 상태·새 세대·새 설정이 이미 커밋돼
     * 있어, 재임베딩이 새 모델로 수행된다. {@code reindexExecutor}(단일 스레드)로 직렬화돼 잡이 병렬로 돌지 않는다.
     *
     * <p>{@code event.userId()}로 {@link AiContextFactory#forUser(long)}를 호출해 컨텍스트를 새로 고정한다 —
     * 이 @Async 스레드는 설정 변경을 요청한 사용자의 요청 스레드가 아니므로 {@code CurrentUserProvider}를 참조할 수 없다(참조해서도 안 된다).
     */
    @Async("reindexExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmbeddingModelChanged(EmbeddingModelChangedEvent event) {
        reindexUser(event.userId(), event.generation(), contextFactory.forUser(event.userId()));
    }

    /**
     * {@code userId} 소유 활성 memory 전체를 새 임베딩 모델로 재임베딩한다(벡터만; tsvector 는 건드리지 않는다). 잡 시작 시점에 {@code
     * ctx}의 임베딩 클라이언트를 하나로 고정해 모든 문서를 같은 모델로 임베딩하고(문서마다 재해석하지 않음 — 중간에 설정이 바뀌어도 이 잡은 끝까지 같은 모델로
     * 완주), 종료 상태는 세대 펜싱을 통과할 때만 쓴다.
     *
     * @param userId 재색인 대상 소유자. 이 값으로만 memory 조회·상태 UPDATE 를 스코프한다(교차유출 금지).
     * @param myGeneration 이 잡이 부여받은 세대 토큰. {@code userId} 행의 현재 세대가 아직 이 값과 같을 때만 READY/FAILED 를
     *     쓴다.
     * @param ctx 잡 시작 시점에 고정된 사용자 AI 컨텍스트 스냅샷.
     */
    public void reindexUser(long userId, long myGeneration, UserAiContext ctx) {
        log.info("재색인 시작 (user={}, generation={})", userId, myGeneration);
        try {
            // 잡 시작 시점에 한 번만 고정(pin) — 문서마다 재해석하지 않아 도중 설정이 바뀌어도 이 잡은
            // 끝까지 같은 모델로 임베딩한다(모델 혼재 방지). 미설정이면 AiNotConfiguredException 이 곧장
            // catch 로 떨어져 FAILED 로 드러난다(조용한 실패 금지).
            EmbeddingClient pinned = ctx.requireEmbedding();
            List<StoredMemory> actives = memories.activeOf(userId);
            List<Long> unreadable = new ArrayList<>();
            for (StoredMemory m : actives) {
                // 저장된 카드를 유형 스키마로 되읽는다 — 재색인도 카드 정규화를 거친 값으로 임베딩한다.
                // 못 읽는 카드 한 건이 잡 전체를 죽이지 않게 건너뛴다: 전에는 여기서 던지면 아래 catch 가
                // embedding_status=FAILED 로 전이했고, HybridSearchService 는 READY 만 허용하므로 그 사용자의
                // **벡터 채널이 통째로 꺼졌다**. 게다가 그 카드는 영원히 못 읽으므로 FAILED 가 고착된다.
                MemoryCard card = cardCodec.readOrNull(m.type(), m.structured());
                if (card == null) {
                    // 낡은 벡터를 남기면 신구 모델이 섞여 READY 가 거짓이 된다 — 지워서 이 카드만
                    // BM25 전용으로 격하하고, 나머지 벡터 공간의 일관성(=READY 의 의미)을 지킨다.
                    searchIndex.clearEmbeddings(m.id());
                    unreadable.add(m.id());
                    continue;
                }
                // 색인 절차는 SearchIndex 가 소유한다 — 승인 경로와 같은 규약을 쓴다(갈라짐 방지).
                searchIndex.reembed(m.id(), m.type(), card, pinned);
            }
            if (!unreadable.isEmpty()) {
                log.warn(
                        "재색인: 카드를 읽을 수 없어 벡터를 비운 memory {}건 {} (BM25 로만 검색된다)",
                        unreadable.size(),
                        unreadable);
            }
            setEmbeddingStatusIfCurrent(userId, EmbeddingStatus.READY, myGeneration);
            log.info(
                    "재색인 완료: user={} 활성 memory {}건 (generation={})",
                    userId,
                    actives.size(),
                    myGeneration);
        } catch (Exception e) {
            setEmbeddingStatusIfCurrent(userId, EmbeddingStatus.FAILED, myGeneration);
            log.error("재색인 실패 — 상태 FAILED (user={}, generation={})", userId, myGeneration, e);
        }
    }

    /**
     * {@code userId} 소유 model_setting 의 임베딩 상태를 전이하되, 행의 현재 세대가 아직 {@code myGeneration}과 같을 때만
     * 쓴다(세대 펜싱, user_id 스코프). 더 새로운 세대가 이미 그 사용자 행에 반영돼 있으면(= 뒤이은 설정 변경이 새 재색인을 예약함) 이 잡은
     * 뒤처진(stale) 것이므로 상태를 쓰지 않고 건너뛴다 — 뒤늦은 앞선 잡이 새 재색인 위에 READY/FAILED 를 덮어쓰지 못하게 한다. user_id 조건이
     * 있어 다른 사용자의 행에는 애초에 영향을 줄 수 없다(교차유출 불가).
     *
     * <p>읽고-검사하고-쓰는 방식이 아니라 {@link ModelSettingRepository#updateEmbeddingStatusIfGeneration(long,
     * String, long)} 원자적 조건부 UPDATE 하나로 처리한다 — embedding_status 컬럼만 건드리고 embedding_generation 은 절대
     * 읽거나 다시 쓰지 않으므로, 전체 엔티티 save 가 다른 트랜잭션의 세대 증가분을 덮어쓰는 lost update 가 구조적으로 불가능하다.
     */
    public void setEmbeddingStatusIfCurrent(long userId, String status, long myGeneration) {
        int updated = settings.setEmbeddingStatusIfGeneration(userId, status, myGeneration);
        if (updated == 0) {
            log.info(
                    "재색인(user={}, generation={})은 더 새로운 generation에 의해 대체됨 — 상태({}) 전이 건너뜀",
                    userId,
                    myGeneration,
                    status);
        }
    }
}
