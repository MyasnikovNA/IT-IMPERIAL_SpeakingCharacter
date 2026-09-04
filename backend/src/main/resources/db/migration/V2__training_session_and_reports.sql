-- Добавляет lifecycle тренировки и постоянный структурированный отчёт evaluation.
ALTER TABLE chat_sessions
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FINISHED', 'FAILED')),
    ADD COLUMN scenario_id VARCHAR(255),
    ADD COLUMN finished_at TIMESTAMPTZ;

CREATE TABLE training_reports (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE REFERENCES chat_sessions(id),
    overall_score INT NOT NULL CHECK (overall_score BETWEEN 1 AND 5),
    summary TEXT NOT NULL,
    recommendations JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE training_report_items (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES training_reports(id) ON DELETE CASCADE,
    criterion_name TEXT NOT NULL,
    score INT NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment TEXT NOT NULL,
    evidence TEXT NOT NULL
);
