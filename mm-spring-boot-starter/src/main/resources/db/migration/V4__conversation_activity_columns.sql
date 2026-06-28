-- =============================================================================
-- V4 - Activite des conversations (E3-M5)
-- Choix d'implementation : persister message_count et last_message_at dans
-- conversation pour eviter de coupler les lectures REST au schema interne
-- SPRING_AI_CHAT_MEMORY de Spring AI.
-- =============================================================================

ALTER TABLE conversation ADD COLUMN message_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD COLUMN last_message_at TEXT;

UPDATE conversation
SET message_count = COALESCE((
        SELECT COUNT(*)
        FROM SPRING_AI_CHAT_MEMORY memory
        WHERE memory.conversation_id = conversation.id
    ), 0),
    last_message_at = (
        SELECT strftime('%Y-%m-%dT%H:%M:%fZ', MAX(memory.timestamp) / 1000.0, 'unixepoch')
        FROM SPRING_AI_CHAT_MEMORY memory
        WHERE memory.conversation_id = conversation.id
    );

CREATE INDEX idx_conversation_project_status_last_message
    ON conversation (project_id, status, last_message_at);
