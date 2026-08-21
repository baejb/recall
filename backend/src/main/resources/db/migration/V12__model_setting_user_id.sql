-- model_setting 사용자별 전환. 단일행(id=1) 전제 검증 후 부트스트랩(1) 귀속, 위반 시 fail-loud.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = 1) THEN
    RAISE EXCEPTION '부트스트랩 사용자(app_user.id=1) 없음 — V11 시드 누락';
  END IF;
  IF (SELECT count(*) FROM model_setting) > 1
     OR EXISTS (SELECT 1 FROM model_setting WHERE id <> 1) THEN
    RAISE EXCEPTION 'model_setting 단일행(id=1) 전제 위반 — 소유자 자동 귀속 불가, 수동 마이그레이션 필요';
  END IF;
END $$;
ALTER TABLE model_setting ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE RESTRICT;
UPDATE model_setting SET user_id = 1 WHERE id = 1;
ALTER TABLE model_setting ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE model_setting ADD CONSTRAINT uq_model_setting_user UNIQUE (user_id);
