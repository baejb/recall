-- Recall 도메인 스키마 (MVP). pgvector 확장은 V1에서 활성화됨.
-- 단일 사용자 셀프호스트: user 테이블/user_id 컬럼을 두지 않는다(격리 = 사용자당 DB 1개).
-- 스키마는 Flyway 소유(ddl-auto=none). 기존 마이그레이션은 수정하지 않고 새 버전으로만 추가한다.

-- ── capture: 붙여넣은 원문(마스킹 후). 검색 인덱싱 대상이 아니라 "근거"로 보관한다. ──
CREATE TABLE capture (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type  VARCHAR(16)  NOT NULL DEFAULT 'chat',    -- chat | markdown | log | plain
    raw_text     TEXT         NOT NULL,                   -- 마스킹된 원문
    masked_spans JSONB        NOT NULL DEFAULT '[]',       -- [{start,end,type}] 사용자 검토/복원용
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── memory: 승인된 구조화 카드. 원문 1개 → 메모리 N개 (1:N, capture_id). ──
CREATE TABLE memory (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    capture_id  BIGINT       NOT NULL REFERENCES capture (id) ON DELETE CASCADE, -- 나온 원문(1:N)
    type        VARCHAR(32)  NOT NULL,                     -- KNOWLEDGE | TROUBLESHOOTING
    title       VARCHAR(500) NOT NULL,
    project     VARCHAR(200),
    component   VARCHAR(200),
    summary     TEXT,
    structured  JSONB        NOT NULL DEFAULT '{}',         -- 유형별 필드(증상/원인/해결 또는 사실/문서)
    status      VARCHAR(16)  NOT NULL DEFAULT 'active',     -- active | superseded | incorrect
    confidence  REAL,                                      -- 0..1
    search_tsv  tsvector,                                  -- 키워드(BM25) 검색용
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── memory_embedding: 제네릭 지문 테이블. memory 1개 → 지문 N개(kind별 행). ──
-- 유형별 벡터 컬럼을 memory에 박지 않는다(architecture.md 가드레일 3): 새 지문 종류 = 행 추가로 끝.
CREATE TABLE memory_embedding (
    memory_id BIGINT       NOT NULL REFERENCES memory (id) ON DELETE CASCADE,
    kind      VARCHAR(32)  NOT NULL,                       -- problem | solution | fact | document | ...
    vector    vector(1024) NOT NULL,                       -- 임베딩 차원(기본 voyage-3 = 1024)
    PRIMARY KEY (memory_id, kind)
);

-- ── review_queue: 유일한 승인 게이트. 승인 전에는 memory에 반영하지 않는다(불변 원칙). ──
CREATE TABLE review_queue (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    capture_id   BIGINT       NOT NULL REFERENCES capture (id) ON DELETE CASCADE,
    memory_id    BIGINT       REFERENCES memory (id) ON DELETE SET NULL, -- 재발/충돌 판정 대상(있으면)
    judgement    VARCHAR(16)  NOT NULL,                     -- NEW | RECURRENCE | SUPPLEMENT | CONFLICT
    judge_reason TEXT,
    proposed     JSONB        NOT NULL DEFAULT '{}',         -- 승인 대기 중인 추출 구조
    status       VARCHAR(16)  NOT NULL DEFAULT 'pending',    -- pending | approved | edited | rejected
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);

-- ── 인덱스 ──
CREATE INDEX idx_memory_capture ON memory (capture_id);
CREATE INDEX idx_memory_type_status ON memory (type, status);
CREATE INDEX idx_memory_project ON memory (project);
CREATE INDEX idx_memory_search_tsv ON memory USING gin (search_tsv);
CREATE INDEX idx_memory_embedding_vec ON memory_embedding USING hnsw (vector vector_cosine_ops);
CREATE INDEX idx_review_queue_status ON review_queue (status);
