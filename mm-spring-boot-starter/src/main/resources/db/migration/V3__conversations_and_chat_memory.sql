-- =============================================================================
-- V3 — Conversations + mémoire chat JDBC (E2-M2)
-- ADR-022 : source de vérité DB. ADR-014 : même datasource SQLite.
-- Clé d'isolation mémoire = id de ConversationEntity (UUID v4).
--
-- La table SPRING_AI_CHAT_MEMORY reproduit le schéma officiel SQLite de
-- Spring AI 1.1.7 (schema-sqlite.sql du module spring-ai-model-chat-memory-repository-jdbc).
-- timestamp = INTEGER (epoch millis) — pas TEXT, contrairement au dialecte PostgreSQL.
-- Le bean JdbcChatMemoryRepository est câblé manuellement dans MmChatMemoryAutoConfiguration
-- avec JdbcChatMemoryRepositoryDialect.from(dataSource) qui détecte automatiquement SQLite.
-- =============================================================================

CREATE TABLE conversation (
    id          TEXT PRIMARY KEY,                                     -- UUID v4 (clé d'isolation mémoire)
    project_id  TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title       TEXT,                                                 -- nullable, rempli en E2-M5 (LLM title)
    started_at  TEXT NOT NULL,                                        -- ISO-8601
    status      TEXT NOT NULL DEFAULT 'OPEN'                         -- OPEN | ARCHIVED
);

-- Index pour listByProject (GET /projects/{projectId}/conversations).
CREATE INDEX idx_conversation_project ON conversation (project_id);

-- Table officielle Spring AI 1.1.7 — dialecte SQLite.
-- Pas créée par Spring AI (initialize-schema non invoqué) : Flyway est SSOT (ADR-022).
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id TEXT    NOT NULL,
    content         TEXT    NOT NULL,
    type            TEXT    NOT NULL,
    timestamp       INTEGER NOT NULL,     -- epoch millis (SQLite INTEGER, pas TIMESTAMP)
    CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, timestamp);
