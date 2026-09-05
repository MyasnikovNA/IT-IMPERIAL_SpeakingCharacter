CREATE TABLE IF NOT EXISTS integrated_training_sessions (
    id UUID PRIMARY KEY,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_integrated_training_sessions_updated_at
    ON integrated_training_sessions(updated_at DESC);
