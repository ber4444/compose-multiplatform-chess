CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS passages (
    id BIGSERIAL PRIMARY KEY,
    source_id TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    text TEXT NOT NULL,
    embedding vector(384) NOT NULL
);

-- Structured retrieval keys, added after embedding-only retrieval was measured returning the wrong
-- opening about half the time. `moves` is the normalized SAN prefix (MoveSequence.normalizePgn) and
-- is what identifies an opening exactly; `eco` scopes the vector fallback to the right volume.
-- Both stay NULL until `SeedMain` reseeds, and PostgresPassageRepository falls back to plain vector
-- search in that case, so applying this schema alone changes nothing.
ALTER TABLE passages ADD COLUMN IF NOT EXISTS eco TEXT;
ALTER TABLE passages ADD COLUMN IF NOT EXISTS moves TEXT;
CREATE INDEX IF NOT EXISTS passages_eco_idx ON passages (eco);
