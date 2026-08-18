-- 멀티유저 전환 2/2: 사용자 데이터 테이블에 user_id 파티셔닝.
-- 교차유출 금지(불변 원칙): 모든 사용자 데이터 행은 소유자(user_id)를 갖고,
-- 모든 조회/검색 쿼리는 user_id로 스코프한다. 격리를 join 정확성에만 맡기지 않기 위해
-- 검색·목록이 직접 때리는 테이블(memory, review_queue)에도 user_id를 비정규화한다.
--
-- 백필 전략: "깨끗한 시작"(팀 결정) — 릴리스 전 dev 데이터만 존재하므로 기존 도메인 데이터를
-- 비우고 user_id NOT NULL 로 시작한다. TRUNCATE ... CASCADE 는 FK 로 참조하는 모든 테이블을
-- ON DELETE 절과 무관하게 함께 비운다 — capture 를 비우면 memory / memory_embedding / review_queue
-- 가 전부 비워진다(review_queue.memory_id 가 ON DELETE SET NULL 이어도 capture_id FK 로 함께 truncate).
--
-- ⚠️ 의도된 1회성 리셋(실수 아님) — 실행 시점의 모든 도메인 데이터를 되돌릴 수 없이 삭제한다.
-- 코드리뷰(Codex P1)에서 "실데이터 있는 DB에 돌면 유실"로 지적됐고, 팀이 이를 인지한 채
-- 배포 전 단계라 clean-start 를 선택했다. 실데이터를 담은 DB에 배포하기 전에는 이 TRUNCATE 를
-- 부트스트랩 user 1 로의 backfill(UPDATE)로 교체해야 한다. 불변원칙 "삭제 대신 상태 보존"의 예외.
TRUNCATE TABLE capture CASCADE;

-- ── user_id 컬럼 (NOT NULL FK). 테이블이 비어 있으므로 기본값 없이 NOT NULL 부여 가능. ──
ALTER TABLE capture
    ADD COLUMN user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE memory
    ADD COLUMN user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE review_queue
    ADD COLUMN user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE;
-- memory_embedding 은 user_id 를 두지 않는다: 벡터 검색이 항상 memory 와 조인하므로
-- memory.user_id 필터로 격리된다(비정규화의 비정규화를 피함). 리트리버는 반드시
-- memory.user_id = ? 조건을 건다.

-- ── FK / 스코프 인덱스 ──
CREATE INDEX idx_capture_user ON capture (user_id);
-- review_queue 의 user_id 단독 조회/FK 는 아래 (user_id, status) 복합 인덱스의 선두 프리픽스로 커버되므로
-- 별도 (user_id) 단독 인덱스는 두지 않는다(쓰기 비용만 늘고 읽기 이득 없음).

-- 사용자별 목록·검토 조회는 user_id 선두 인덱스로 서빙한다.
CREATE INDEX idx_memory_user_status_created_id
    ON memory (user_id, status, created_at DESC, id DESC);
CREATE INDEX idx_memory_user_status_type_created_id
    ON memory (user_id, status, type, created_at DESC, id DESC);

-- idx_memory_status_created_id(status, created_at DESC, id DESC)는 남긴다:
-- 재색인 배경잡(ReindexService)은 전 사용자 active 기억을 user_id 조건 없이 훑으므로,
-- user_id 선두 인덱스로는 서빙되지 않는다(전역 스윕). 유형+status 전역 조회는 없어
-- idx_memory_status_type_created_id 만 제거한다.
DROP INDEX idx_memory_status_type_created_id;

DROP INDEX idx_review_queue_status;
CREATE INDEX idx_review_queue_user_status ON review_queue (user_id, status);
