package com.recall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.capture.CaptureAccess;
import com.recall.common.type.MemoryType;
import com.recall.memory.repository.MemoryRepository;
import com.recall.memory.service.entity.Memory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 🔴 memory 의 소유자({@code user_id})는 <b>capture 에서만</b> 파생된다.
 *
 * <p><b>왜 이 테스트가 필요해졌나</b> — 전에는 {@code Memory} 생성자가 {@code Capture} 엔티티를 받아 이 파생을 <b>타입으로</b>
 * 강제했다. 그 강제는 모듈 경계를 대가로 얻은 것이었고(memory 가 capture 의 엔티티 클래스를 알아야 했다), 연관을 FK 값으로 낮추면서 강제가 사라졌다 — 이제
 * 생성자에 아무 {@code long} 이나 넣을 수 있다.
 *
 * <p>강제가 코드 규약으로 내려왔으므로 <b>테스트가 그 자리를 대신한다</b>: 유일한 쓰기 경로가 호출자의 값을 믿지 않고 capture 에 물어보는지 고정한다. 이게
 * 깨지면 남의 원문으로 만든 카드가 내 소유로 저장될 수 있다(교차유출).
 */
@Tag("unit")
@Tag("release-gate")
class MemoryAccessOwnerDerivationTest {

    private static final long CAPTURE_ID = 7L;
    private static final long CAPTURE_OWNER = 42L;

    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final CaptureAccess captures = mock(CaptureAccess.class);

    @Test
    @DisplayName("createApproved 는 capture 의 소유자를 memory.user_id 로 쓴다(호출자에게 묻지 않는다)")
    void ownerComesFromCaptureNotCaller() {
        when(captures.ownerOf(CAPTURE_ID)).thenReturn(CAPTURE_OWNER);
        // 저장 결과는 id 가 부여된 행이어야 한다(미영속 엔티티를 그대로 돌려주면 반환값 언박싱에서 터진다).
        // 검증 대상은 반환값이 아니라 <b>save 에 넘긴 엔티티</b>라, 아래 captor 가 실제 인자를 잡는다.
        Memory persisted = mock(Memory.class);
        when(persisted.getId()).thenReturn(1L);
        when(repository.save(any(Memory.class))).thenReturn(persisted);

        new MemoryAccess(repository, captures)
                .createApproved(CAPTURE_ID, MemoryType.KNOWLEDGE, "제목", "{}");

        // 서명에 userId 가 없다는 것만으로는 부족하다 — 실제로 capture 에 물어 그 값을 넣었는지 본다.
        verify(captures).ownerOf(CAPTURE_ID);
        ArgumentCaptor<Memory> saved = ArgumentCaptor.forClass(Memory.class);
        verify(repository).save(saved.capture());
        assertEquals(CAPTURE_OWNER, saved.getValue().getUserId());
        assertEquals(CAPTURE_ID, saved.getValue().getCaptureId());
    }
}
