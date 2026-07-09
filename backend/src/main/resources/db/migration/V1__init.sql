-- Recall initial schema (PostgreSQL + pgvector)
-- Single-user, self-hosted: no user table / user_id column (isolation = one DB per user).

CREATE EXTENSION IF NOT EXISTS vector;

-- Embedding dimension for the default embedding model (voyage-3 = 1024).
-- Change here + re-embed if you swap the embedding model.

-- ── capture: original pasted conversation/document, masked before extraction ──
CREATE TABLE capture (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type  VARCHAR(32)  NOT NULL DEFAULT 'chat',   -- chat | markdown | log
    raw_text     TEXT         NOT NULL,                  -- masked original, kept as evidence (NOT indexed for search)
    masked_spans JSONB        NOT NULL DEFAULT '[]',      -- [{start,end,type}] for user review/restore
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── memory: structured, approved knowledge/troubleshooting record ──
CREATE TABLE memory (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type               VARCHAR(32)  NOT NULL,             -- TROUBLESHOOTING | KNOWLEDGE
    title              VARCHAR(500) NOT NULL,
    project            VARCHAR(200),
    component          VARCHAR(200),
    summary            TEXT,
    structured         JSONB        NOT NULL DEFAULT '{}', -- attempts[]/root_cause/resolution/status OR facts[]/document
    status             VARCHAR(32)  NOT NULL DEFAULT 'active', -- active | superseded | incorrect
    confidence         REAL,                              -- 0..1
    -- Troubleshooting: problem vs solution embeddings are kept separate (see design doc 3.4)
    problem_embedding  vector(1024),
    solution_embedding vector(1024),
    -- Knowledge: fact vs document embeddings
    fact_embedding     vector(1024),
    document_embedding vector(1024),
    search_tsv         tsvector,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── memory_capture: N:M link (a memory is backed by one or more captures) ──
CREATE TABLE memory_capture (
    memory_id  BIGINT NOT NULL REFERENCES memory(id)  ON DELETE CASCADE,
    capture_id BIGINT NOT NULL REFERENCES capture(id) ON DELETE CASCADE,
    PRIMARY KEY (memory_id, capture_id)
);

-- ── review_queue: the single human-in-the-loop gate. Nothing reaches memory without approval. ──
CREATE TABLE review_queue (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    capture_id   BIGINT       REFERENCES capture(id) ON DELETE SET NULL,
    memory_id    BIGINT       REFERENCES memory(id)  ON DELETE SET NULL, -- target of a reentry/conflict judgement, if any
    judgement    VARCHAR(32)  NOT NULL,             -- NEW | RECURRENCE | SUPPLEMENT | CONFLICT
    judge_reason TEXT,
    proposed     JSONB        NOT NULL DEFAULT '{}', -- the extracted structure awaiting approval
    status       VARCHAR(32)  NOT NULL DEFAULT 'pending', -- pending | approved | edited | rejected
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);

-- ── audit: every approval/edit/mask/state transition ──
CREATE TABLE audit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action      VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(64)  NOT NULL,
    entity_id   BIGINT,
    detail      JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── indexes ──
CREATE INDEX idx_memory_problem_embedding  ON memory USING hnsw (problem_embedding  vector_cosine_ops);
CREATE INDEX idx_memory_solution_embedding ON memory USING hnsw (solution_embedding vector_cosine_ops);
CREATE INDEX idx_memory_fact_embedding     ON memory USING hnsw (fact_embedding     vector_cosine_ops);
CREATE INDEX idx_memory_document_embedding ON memory USING hnsw (document_embedding vector_cosine_ops);
CREATE INDEX idx_memory_search_tsv         ON memory USING gin  (search_tsv);
CREATE INDEX idx_memory_project            ON memory (project);
CREATE INDEX idx_memory_type_status        ON memory (type, status);
CREATE INDEX idx_review_queue_status       ON review_queue (status);
