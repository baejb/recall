package com.recall.memory;

import com.recall.capture.CaptureAccess;
import com.recall.common.type.MemoryType;
import com.recall.memory.repository.MemoryRepository;
import com.recall.memory.service.entity.Memory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 모듈이 memory 에 닿는 <b>유일한 창구</b>.
 *
 * <p><b>왜 생겼나</b> — review·search·store 가 {@code MemoryRepository} 를 직접 잡고 {@code Memory} 엔티티를
 * 주고받았다. 그중 제일 무거운 건 승인 경로다: {@code ReviewService} 가 {@code new Memory(...)} 로 행을 만들어 직접 저장했으므로,
 * memory 의 저장 규약이 <b>두 곳</b>에 존재했다 — memory 모듈이 자기 테이블에 쓰는 경로와 review 가 쓰는 경로. 나중에 memory 쪽에 불변식을
 * 하나 추가하면 review 경로는 조용히 그걸 지나지 않고, 승인으로 만들어진 행만 규약을 벗어난 채 쌓인다.
 *
 * <p>{@code MemoryService} 와 나누는 기준: 그쪽은 HTTP 유스케이스(목록·상세·상태 전이), 이쪽은 <b>모듈 간 계약</b>이다. 나가는 값은 항상
 * {@link StoredMemory} 로, 엔티티를 모듈 밖에 내보내지 않는다.
 */
@Service
public class MemoryAccess {

    private final MemoryRepository memoryRepository;
    private final CaptureAccess captures;

    public MemoryAccess(MemoryRepository memoryRepository, CaptureAccess captures) {
        this.memoryRepository = memoryRepository;
        this.captures = captures;
    }

    /**
     * 승인된 카드를 영구 memory 로 만든다(불변 원칙 1: 승인 게이트를 지난 유일한 쓰기 경로).
     *
     * <p><b>userId 를 인자로 받지 않는 이유</b> — 소유자는 {@code capture.user_id} <b>에서만</b> 파생한다. 호출자가 userId 를
     * 함께 넘길 수 있게 하면 capture 소유자와 다른 값을 넘길 여지가 생기고, 그건 교차유출(🔴)이다. 그래서 여기서 capture 의 공개 계약에 소유자를 물어
     * 넣는다 — <b>이 모듈의 유일한 쓰기 경로가 그 파생을 책임진다</b>.
     *
     * @return 새로 만들어진 memory.id
     */
    @Transactional
    public long createApproved(long captureId, MemoryType type, String title, String structured) {
        long ownerUserId = captures.ownerOf(captureId);
        return memoryRepository
                .save(new Memory(captureId, ownerUserId, type, title, structured))
                .getId();
    }

    /**
     * 소유자 스코프로 한 건 조회.
     *
     * <p>id 만으로 찾지 않는다 — 검색 인덱스가 이미 userId 로 스코프했더라도, 재조회 지점에서 소유자 조건을 한 번 더 강제해 교차유출 방어를 이중화한다(회귀
     * 가드).
     */
    @Transactional(readOnly = true)
    public Optional<StoredMemory> findOwned(long memoryId, long userId) {
        return memoryRepository.findByIdAndUserId(memoryId, userId).map(MemoryAccess::toStored);
    }

    /** 이 사용자의 활성 memory 전체(재색인 대상). */
    @Transactional(readOnly = true)
    public List<StoredMemory> activeOf(long userId) {
        return memoryRepository.findActiveByUserId(userId).stream()
                .map(MemoryAccess::toStored)
                .toList();
    }

    /**
     * id 목록을 <b>주어진 순서대로</b> 조회한다.
     *
     * <p>순서 유지가 계약인 이유: 호출자(하이브리드 검색)는 이미 결정론 융합으로 순위를 정해 놓았고, 여기서 DB 반환 순서로 섞이면 그 융합이 무의미해진다. 없는
     * id 는 조용히 빠진다(그 사이 상태가 바뀐 행) — 목록이 짧아지는 것은 유실이 아니라 최신 상태다.
     */
    @Transactional(readOnly = true)
    public List<StoredMemory> byIdsInOrder(List<Long> ids) {
        Map<Long, StoredMemory> found =
                memoryRepository.findAllById(ids).stream()
                        .map(MemoryAccess::toStored)
                        .collect(Collectors.toMap(StoredMemory::id, Function.identity()));
        return ids.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    /**
     * 이 사용자가 소유한 memory 건수.
     *
     * <p><b>왜 공개 계약에 있나</b> — {@code auth} 의 부팅 안내가 이 수를 필요로 하는데, 전에는 그쪽에서 {@code SELECT count(*)
     * FROM memory WHERE user_id = ?} 를 직접 날렸다. 그러면 이 모듈이 {@code user_id} 컬럼명을 바꾸는 순간 남의 모듈 부팅이 깨지고
     * <b>컴파일 타임 신호가 없다</b>. 메서드로 내면 같은 변경이 컴파일 에러로 드러난다.
     */
    @Transactional(readOnly = true)
    public long countOwnedBy(long userId) {
        return memoryRepository.countByUserId(userId);
    }

    private static StoredMemory toStored(Memory memory) {
        return new StoredMemory(memory.getId(), memory.getType(), memory.getStructured());
    }
}
