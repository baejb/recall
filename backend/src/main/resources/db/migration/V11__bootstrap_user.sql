-- 멀티유저 전환 3/3: 부트스트랩 사용자 시드.
-- OAuth 인증이 배선되기 전까지 BootstrapCurrentUserProvider 가 반환하는 기본 소유자(id=1).
-- 단일 사용자 시절 동작과 동일하게, 모든 쓰기/조회가 이 사용자로 스코프된다.
-- app_user.id 는 GENERATED ALWAYS AS IDENTITY 이므로 명시 id 삽입에 OVERRIDING SYSTEM VALUE 를 쓴다.
INSERT INTO app_user (id, provider, subject, email, display_name)
OVERRIDING SYSTEM VALUE
VALUES (1, 'bootstrap', 'bootstrap', NULL, 'Bootstrap User');

-- 실제 사용자가 로그인해 생성될 때 id 충돌이 없도록 identity 시퀀스를 2부터로 올린다.
ALTER TABLE app_user ALTER COLUMN id RESTART WITH 2;
