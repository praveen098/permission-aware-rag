-- PARAG: Permission-Aware RAG - database schema
-- Design decision D3 (see docs/notes/day-01): allowed_groups is DENORMALIZED
-- onto chunks so retrieval is a single indexed query with the permission
-- filter applied BEFORE ranking. ACL changes update this array without
-- touching embeddings.

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- Identity ----------
CREATE TABLE users (
    id          SERIAL PRIMARY KEY,
    email       TEXT UNIQUE NOT NULL,
    display_name TEXT NOT NULL
);

CREATE TABLE groups (
    id          SERIAL PRIMARY KEY,
    name        TEXT UNIQUE NOT NULL          -- e.g. 'engineering', 'hr', 'leadership'
);

CREATE TABLE user_groups (
    user_id     INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    group_id    INT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, group_id)
);

-- ---------- Documents ----------
CREATE TABLE documents (
    id          TEXT PRIMARY KEY,             -- source-native id, e.g. 'CONF-1042', 'JIRA-881'
    source_type TEXT NOT NULL CHECK (source_type IN ('confluence', 'jira')),
    space       TEXT NOT NULL,                -- e.g. 'ENG', 'HR', 'FIN'
    title       TEXT NOT NULL,
    version     INT  NOT NULL DEFAULT 1,      -- monotonically increasing; used for idempotency
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Source of truth for permissions: which groups may READ a document.
CREATE TABLE document_acls (
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    group_id    INT  NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, group_id)
);

-- ---------- Chunks (the retrieval unit) ----------
CREATE TABLE chunks (
    id              BIGSERIAL PRIMARY KEY,
    document_id     TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    doc_version     INT  NOT NULL,            -- version of the doc this chunk came from
    chunk_index     INT  NOT NULL,
    content         TEXT NOT NULL,
    embedding       vector(384),              -- sentence-transformers/all-MiniLM-L6-v2
    tsv             tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    -- DENORMALIZED permission filter. Kept in sync by the indexer consumer.
    allowed_groups  INT[] NOT NULL,
    UNIQUE (document_id, doc_version, chunk_index)
);

-- Vector index: HNSW, cosine distance.
CREATE INDEX idx_chunks_embedding ON chunks
    USING hnsw (embedding vector_cosine_ops);

-- Keyword index for BM25-style full-text retrieval.
CREATE INDEX idx_chunks_tsv ON chunks USING gin (tsv);

-- Permission filter index: GIN on int array supports `allowed_groups && $1`.
CREATE INDEX idx_chunks_allowed_groups ON chunks USING gin (allowed_groups);

CREATE INDEX idx_chunks_document ON chunks (document_id);

-- ---------- Seed identity data ----------
INSERT INTO groups (name) VALUES
    ('engineering'), ('hr'), ('finance'), ('leadership'), ('all-staff');

INSERT INTO users (email, display_name) VALUES
    ('asha@corp.io',  'Asha (Engineer)'),
    ('ravi@corp.io',  'Ravi (HR Manager)'),
    ('meera@corp.io', 'Meera (CFO)'),
    ('dev@corp.io',   'Dev (Intern)');

INSERT INTO user_groups (user_id, group_id)
SELECT u.id, g.id FROM users u JOIN groups g ON (
    (u.email = 'asha@corp.io'  AND g.name IN ('engineering', 'all-staff')) OR
    (u.email = 'ravi@corp.io'  AND g.name IN ('hr', 'all-staff')) OR
    (u.email = 'meera@corp.io' AND g.name IN ('finance', 'leadership', 'all-staff')) OR
    (u.email = 'dev@corp.io'   AND g.name IN ('all-staff'))
);
