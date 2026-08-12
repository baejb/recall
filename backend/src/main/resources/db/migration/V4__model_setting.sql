-- 단일 사용자 전역 모델 설정. 항상 단일 행(id=1). 키는 애플리케이션 레벨 암호문으로만 저장.
CREATE TABLE model_setting (
    id                     BIGINT PRIMARY KEY,
    chat_provider          TEXT NOT NULL DEFAULT 'anthropic',
    chat_model             TEXT NOT NULL DEFAULT 'claude-opus-4-8',
    chat_api_key_enc       TEXT,
    embedding_provider     TEXT NOT NULL DEFAULT 'voyage',
    embedding_model        TEXT,
    embedding_api_key_enc  TEXT,
    embedding_status       TEXT NOT NULL DEFAULT 'READY',   -- READY | REINDEXING | FAILED
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 기본 행 seed (env 키를 쓰는 초기 상태; 암호문 컬럼은 비움).
INSERT INTO model_setting (id) VALUES (1);
