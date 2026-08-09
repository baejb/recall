-- Recall — 최소 실행 골격의 baseline 마이그레이션 (PostgreSQL + pgvector).
-- 스키마는 Flyway 소유(ddl-auto=none). 실제 도메인 스키마(capture/memory/review 등)는
-- 기능 구현 단계에서 V2 이후 마이그레이션으로 추가한다.
--
-- 단일 사용자 셀프호스트: user 테이블/user_id 컬럼을 두지 않는다(격리 = 사용자당 DB 1개).

-- pgvector 확장 활성화 — DB 연결과 pgvector 사용 가능 여부를 부팅 시 검증한다.
CREATE EXTENSION IF NOT EXISTS vector;
