package com.recall.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recall.common.StrategyRegistry;
import com.recall.llm.EmbeddingClient;
import com.recall.memory.Memory;
import com.recall.memory.MemoryRepository;
import com.recall.memory.MemorySearchStore;
import com.recall.memory.type.SearchRepresentation;
import com.recall.settings.EmbeddingModelChangedEvent;
import com.recall.settings.ModelSetting;
import com.recall.settings.ModelSettingRepository;
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
 * <p>상태 전이: 설정 변경 트랜잭션이 REINDEXING 을 커밋한 뒤(AFTER_COMMIT) 재색인이 돌고, 완료 시 READY / 실패 시 FAILED 로
 * 전이한다(조용한 실패 금지). BM25 tsvector 는 임베딩 모델과 무관하므로 벡터만 다시 만든다.
 *
 * <p>결합: SettingsService 를 직접 참조하지 않는다(그 방향으로 의존하면 SettingsService → ReindexService →
 * EmbeddingClient → SettingsService 빈 순환). 대신 {@link EmbeddingModelChangedEvent} 를 수신하고, 상태는 {@link
 * ModelSettingRepository} 로 직접 쓴다.
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final MemoryRepository memoryRepository;
    private final MemorySearchStore searchStore;
    private final EmbeddingClient embeddingClient;
    private final StrategyRegistry<SearchRepresentation> searchReps;
    private final ModelSettingRepository settingRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReindexService(
            MemoryRepository memoryRepository,
            MemorySearchStore searchStore,
            EmbeddingClient embeddingClient,
            List<SearchRepresentation> searchRepresentations,
            ModelSettingRepository settingRepository) {
        this.memoryRepository = memoryRepository;
        this.searchStore = searchStore;
        this.embeddingClient = embeddingClient;
        this.searchReps = new StrategyRegistry<>(searchRepresentations);
        this.settingRepository = settingRepository;
    }

    /**
     * 설정 변경(임베딩 모델)이 커밋된 뒤 배경에서 재색인을 시작한다. AFTER_COMMIT 이라 REINDEXING 상태와 새 설정이 이미 커밋돼 있어, 재임베딩이 새
     * 모델로 수행된다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmbeddingModelChanged(EmbeddingModelChangedEvent event) {
        reindexAll();
    }

    /** 활성 memory 전체를 새 임베딩 모델로 재임베딩한다(벡터만; tsvector 는 건드리지 않는다). */
    public void reindexAll() {
        log.info("재색인 시작");
        try {
            List<Memory> actives = memoryRepository.findByStatusOrderByCreatedAtDesc("active");
            for (Memory m : actives) {
                Map<String, Object> structured = parseStructured(m.getStructured());
                Map<String, String> texts = searchReps.get(m.getType()).embeddingTexts(structured);
                texts.forEach(
                        (kind, text) ->
                                searchStore.saveEmbedding(
                                        m.getId(), kind, embeddingClient.embedDocument(text)));
            }
            setEmbeddingStatus("READY");
            log.info("재색인 완료: 활성 memory {}건", actives.size());
        } catch (Exception e) {
            setEmbeddingStatus("FAILED");
            log.error("재색인 실패 — 상태 FAILED", e);
        }
    }

    /**
     * model_setting(id=1) 의 임베딩 상태를 전이한다. SettingsService 에 의존하지 않기 위해 직접 쓴다.
     *
     * <p>{@code reindexAll()} 내부에서 self-invocation 으로만 호출돼 프록시를 타지 않으므로 여기의 {@code @Transactional}은
     * 항상 no-op 이었다(제거). 영속은 {@link ModelSettingRepository#save}가 자체 트랜잭션으로 보장한다.
     */
    public void setEmbeddingStatus(String status) {
        ModelSetting s =
                settingRepository
                        .findById(1L)
                        .orElseThrow(() -> new IllegalStateException("model_setting 미초기화"));
        s.setEmbeddingStatus(status);
        settingRepository.save(s);
    }

    private Map<String, Object> parseStructured(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("structured 파싱 실패", e);
        }
    }
}
