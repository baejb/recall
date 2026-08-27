package com.recall.capture;

import com.recall.capture.repository.CaptureRepository;
import com.recall.capture.service.entity.Capture;
import com.recall.capture.service.entity.CaptureStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 모듈이 capture 에 닿는 <b>유일한 창구</b>.
 *
 * <p><b>왜 생겼나</b> — 저장 파이프라인(store)이 {@code CaptureRepository} 를 직접 잡고, {@code Capture} 엔티티를 손에 들고
 * {@code capture.setStatus(DONE)} 으로 <b>남의 모듈 상태를 직접 전이</b>시켰다. 그러면 capture 의 상태 기계를 capture 모듈이
 * 소유하지 않게 된다: "어떤 상태에서 어떤 상태로 갈 수 있는가"를 나중에 capture 쪽에 넣어도 store 경로는 그 규칙을 지나지 않는다.
 *
 * <p>엔티티를 밖으로 내보내지 않는다. JPA 엔티티는 가변이고 영속 컨텍스트에 묶여 있어, 남의 모듈이 세터를 부르면 flush 시점이 호출자 눈에 보이지 않는 곳에서
 * 결정된다.
 *
 * <p>{@code CaptureService} 와 나누는 기준: 그쪽은 HTTP 유스케이스(원문 등록·상태 목록·원문 조회), 이쪽은 <b>모듈 간 계약</b>이다.
 */
@Service
public class CaptureAccess {

    private final CaptureRepository captureRepository;

    public CaptureAccess(CaptureRepository captureRepository) {
        this.captureRepository = captureRepository;
    }

    /**
     * 이 원문의 소유자(app_user.id).
     *
     * <p>비동기 저장 경로는 스레드로컬 {@code CurrentUserProvider} 를 신뢰할 수 없어 <b>DB 의 capture.user_id</b> 를 진실로
     * 삼는다(교차유출 금지). 그래서 이 값이 파이프라인 전체의 소유자 기준점이다.
     */
    @Transactional(readOnly = true)
    public long ownerOf(long captureId) {
        return captureRepository
                .findById(captureId)
                .map(Capture::getUserId)
                .orElseThrow(() -> new IllegalStateException("capture 없음: " + captureId));
    }

    /**
     * 처리 완료로 전이한다.
     *
     * <p><b>새 트랜잭션을 열지 않는다</b>(REQUIRED). 호출자(store)는 검토 항목 저장과 이 전이를 <b>한 트랜잭션으로</b> 커밋해야 한다 —
     * 대기함에는 올랐는데 capture 가 처리중으로 남거나 그 반대가 되면, 재처리 여부를 판단할 근거가 사라진다.
     */
    @Transactional
    public void markDone(long captureId) {
        captureRepository
                .findById(captureId)
                .orElseThrow(() -> new IllegalStateException("capture 없음: " + captureId))
                .setStatus(CaptureStatus.DONE);
    }

    /**
     * 실패 단계를 남기고 FAILED 로 전이한다.
     *
     * <p>리포지토리 쪽이 {@code REQUIRES_NEW} 라 호출자 트랜잭션이 롤백되거나 예외로 무의미해져도 <b>독립 커밋</b>된다(조용한 실패 금지 — 실패는
     * durable 하게 드러나야 한다).
     */
    public void markFailed(long captureId, String stage) {
        captureRepository.markFailed(captureId, stage);
    }

    /**
     * 이 사용자가 소유한 capture 건수.
     *
     * <p><b>왜 공개 계약에 있나</b> — {@code auth} 의 부팅 안내가 이 수를 필요로 하는데, 전에는 그쪽에서 {@code SELECT count(*)
     * FROM capture WHERE user_id = ?} 를 직접 날렸다. 그러면 이 모듈이 {@code user_id} 컬럼명을 바꾸는 순간 남의 모듈 부팅이 깨지고
     * <b>컴파일 타임 신호가 없다</b>. 메서드로 내면 같은 변경이 컴파일 에러로 드러난다.
     */
    @Transactional(readOnly = true)
    public long countOwnedBy(long userId) {
        return captureRepository.countByUserId(userId);
    }
}
