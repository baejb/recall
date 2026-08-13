package com.recall.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.type.SearchRepresentation;
import com.recall.settings.EmbeddingModelChangedEvent;
import com.recall.settings.ModelSettingRepository;
import com.recall.settings.SettingsService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 임베딩 모델이 바뀌면 활성 memory 전체를 새 모델로 재임베딩한다(저장 경로 성격이라 @Async 배경 작업).
 *
 * <p>상태 전이: 설정 변경 트랜잭션이 REINDEXING + 새 세대(generation)를 커밋한 뒤(AFTER_COMMIT) 재색인이 돌고, 완료 시 READY / 실패
 * 시 FAILED 로 전이한다(조용한 실패 금지). BM25 tsvector 는 임베딩 모델과 무관하므로 벡터만 다시 만든다.
 *
 * <p>동시성(P1-c): 재색인 잡은 {@code reindexExecutor}(단일 스레드)로 <b>직렬화</b>되고, 각 잡은 부여받은 세대 토큰을 들고 돈다. 잡 시작
 * 시점에 임베딩 클라이언트를 <b>하나로 고정(pin)</b>해 모든 문서를 같은 모델로 임베딩하고, 종료 시 행의 현재 세대가 아직 자기 세대와 같을 때만 상태를 쓴다. 이
 * 펜싱으로, 임베딩 설정을 연달아 바꿔도 <b>마지막 잡만</b> READY 를 쓰고 뒤늦은 앞선 잡은 상태 쓰기를 건너뛴다 — 반쯤 재색인된(모델 혼재) 인덱스가 READY
 * 로 보이지 않는다.
 *
 * <p>결합: SettingsService 를 순환 없이 참조한다({@code SettingsService}는 이벤트만 발행할 뿐 ReindexService 에 의존하지
 * 않는다). 현재 설정은 {@code settingsService.currentEmbedding()}로 얻고, 상태 쓰기는 {@link
 * ModelSettingRepository}로 직접 한다(상태 쓰기까지 SettingsService 에 의존하지 않아 단순).
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final MemoryRepository memoryRepository;
    private final MemorySearchStore searchStore;
    private final SettingsService settingsService;
    private final EmbeddingClientFactory embeddingClientFactory;
    private final StrategyRegistry<SearchRepresentation> searchReps;
    private final ModelSettingRepository settingRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReindexService(
            MemoryRepository memoryRepository,
            MemorySearchStore searchStore,
            SettingsService settingsService,
            EmbeddingClientFactory embeddingClientFactory,
            List<SearchRepresentation> searchRepresentations,
            ModelSettingRepository settingRepository) {
        this.memoryRepository = memoryRepository;
        this.searchStore = searchStore;
        this.settingsService = settingsService;
        this.embeddingClientFactory = embeddingClientFactory;
        this.searchReps = new StrategyRegistry<>(searchRepresentations);
        this.settingRepository = settingRepository;
    }

    /**
     * 설정 변경(임베딩 모델)이 커밋된 뒤 배경에서 재색인을 시작한다. AFTER_COMMIT 이라 REINDEXING 상태·새 세대·새 설정이 이미 커밋돼 있어,
     * 재임베딩이 새 모델로 수행된다. {@code reindexExecutor}(단일 스레드)로 직렬화돼 잡이 병렬로 돌지 않는다.
     */
    @Async("reindexExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmbeddingModelChanged(EmbeddingModelChangedEvent event) {
        reindexAll(event.generation());
    }

    /**
     * 활성 memory 전체를 새 임베딩 모델로 재임베딩한다(벡터만; tsvector 는 건드리지 않는다). 잡 시작 시점에 임베딩 클라이언트를 하나로 고정해 모든 문서를
     * 같은 모델로 임베딩하고, 종료 상태는 세대 펜싱을 통과할 때만 쓴다.
     *
     * @param myGeneration 이 잡이 부여받은 세대 토큰. 행의 현재 세대가 아직 이 값과 같을 때만 READY/FAILED 를 쓴다.
     */
    public void reindexAll(long myGeneration) {
        log.info("재색인 시작 (generation={})", myGeneration);
        try {
            // 잡 시작 시점의 설정으로 클라이언트를 하나로 고정 — 문서마다 재해석하지 않아 도중 설정이 바뀌어도
            // 이 잡은 끝까지 같은 모델로 임베딩한다(모델 혼재 방지).
            EmbeddingClient pinned =
                    embeddingClientFactory.forSettings(settingsService.currentEmbedding());
            List<Memory> actives = memoryRepository.findByStatusOrderByCreatedAtDesc("active");
            for (Memory m : actives) {
                Map<String, Object> structured = parseStructured(m.getStructured());
                Map<String, String> texts = searchReps.get(m.getType()).embeddingTexts(structured);
                texts.forEach(
                        (kind, text) ->
                                searchStore.saveEmbedding(
                                        m.getId(), kind, pinned.embedDocument(text)));
            }
            setEmbeddingStatusIfCurrent("READY", myGeneration);
            log.info("재색인 완료: 활성 memory {}건 (generation={})", actives.size(), myGeneration);
        } catch (Exception e) {
            setEmbeddingStatusIfCurrent("FAILED", myGeneration);
            log.error("재색인 실패 — 상태 FAILED (generation={})", myGeneration, e);
        }
    }

    /**
     * model_setting(id=1) 의 임베딩 상태를 전이하되, 행의 현재 세대가 아직 {@code myGeneration}과 같을 때만 쓴다(세대 펜싱). 더 새로운
     * 세대가 이미 행에 반영돼 있으면(= 뒤이은 설정 변경이 새 재색인을 예약함) 이 잡은 뒤처진(stale) 것이므로 상태를 쓰지 않고 건너뛴다 — 뒤늦은 앞선 잡이 새
     * 재색인 위에 READY/FAILED 를 덮어쓰지 못하게 한다.
     *
     * <p>읽고-검사하고-쓰는 방식이 아니라 {@link ModelSettingRepository#updateEmbeddingStatusIfGeneration} 원자적
     * 조건부 UPDATE 하나로 처리한다 — embedding_status 컬럼만 건드리고 embedding_generation 은 절대 읽거나 다시 쓰지 않으므로, 전체
     * 엔티티 save 가 다른 트랜잭션의 세대 증가분을 덮어쓰는 lost update 가 구조적으로 불가능하다.
     */
    public void setEmbeddingStatusIfCurrent(String status, long myGeneration) {
        int updated = settingRepository.updateEmbeddingStatusIfGeneration(status, myGeneration);
        if (updated == 0) {
            log.info(
                    "재색인(generation={})은 더 새로운 generation에 의해 대체됨 — 상태({}) 전이 건너뜀",
                    myGeneration,
                    status);
        }
    }

    private Map<String, Object> parseStructured(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("structured 파싱 실패", e);
        }
    }
}
