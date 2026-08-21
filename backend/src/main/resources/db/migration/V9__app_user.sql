-- 멀티유저 전환: SSO(OAuth) 로그인 + 사용자 테이블 도입.
-- 제품 전제가 "단일 사용자 셀프호스트"에서 "여러 사용자가 각자 기억을 갖는" 구조로 바뀐다.
-- 격리 방식 결정: db-per-user 기각 → 공유 DB + user_id 파티셔닝(팀 합의).
-- V1/V2의 "user 테이블을 두지 않는다(격리=사용자당 DB)" 주석은 이 마이그레이션으로 대체된다.
-- 기존 마이그레이션(V1~V8)은 수정하지 않고 새 버전으로만 추가한다(되돌릴 수 없음).
--
-- 이름은 app_user — PostgreSQL 예약어 user 회피.
CREATE TABLE app_user (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider      VARCHAR(32)  NOT NULL DEFAULT 'google',   -- OAuth provider (google | ...)
    subject       VARCHAR(255) NOT NULL,                    -- OAuth 'sub' — provider 내 고유 식별자
    email         VARCHAR(320),
    display_name  VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    -- 같은 provider 안에서 subject는 유일 → 로그인 시 (provider, subject)로 사용자 조회/생성.
    CONSTRAINT uq_app_user_provider_subject UNIQUE (provider, subject)
);
