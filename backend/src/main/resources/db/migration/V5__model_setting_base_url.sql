-- base-url 을 DB 로 끝까지 전달 + 최초 부팅 env 시드 가드.
-- 기존 env 기반 배포는 model_setting 도입 후 provider/model/base-url 을 env 에서 못 읽어 깨졌다.
-- configured=false 인 행은 부팅 시 ModelSettingInitializer 가 env 값으로 1회 시드한다.
ALTER TABLE model_setting ADD COLUMN chat_base_url       TEXT;
ALTER TABLE model_setting ADD COLUMN embedding_base_url  TEXT;
ALTER TABLE model_setting ADD COLUMN configured          BOOLEAN NOT NULL DEFAULT false;
