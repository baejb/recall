-- 캡처 처리 상태 노출(Unit M1) — 비동기 저장 파이프라인의 단계별 종결 상태를 capture 행에 남긴다.
-- 조용한 실패 금지(불변 원칙): 파이프라인이 실패하면 status=FAILED + failed_stage 로 드러낸다.
-- 기존 마이그레이션(V2~V6)은 수정하지 않고 새 버전으로만 추가한다.

-- status: PROCESSING(신규 캡처, 엔티티 생성자가 부여) | DONE(검토대기함 등록 완료) | FAILED(파이프라인 실패)
-- 기존 행은 이미 처리가 끝났다고 보고 DONE 으로 backfill 한다.
ALTER TABLE capture ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DONE';

-- failed_stage: 실패한 파이프라인 단계(classify | extract | judge | review). 성공/처리중이면 NULL.
ALTER TABLE capture ADD COLUMN failed_stage VARCHAR(32);
