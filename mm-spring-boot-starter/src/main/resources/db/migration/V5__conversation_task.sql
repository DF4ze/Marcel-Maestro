-- =============================================================================
-- V5 - Lien conversation <-> taches soumises via submit_task (E3-M6)
-- task_id est une reference faible : les taches vivent en memoire (TaskQueue).
-- =============================================================================

CREATE TABLE conversation_task (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    task_id         TEXT NOT NULL,
    submitted_at    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'RUNNING'
);

CREATE INDEX idx_conversation_task_conv ON conversation_task (conversation_id);
