-- =============================================================================
-- V1 — Table memory_entry (étape 5 — Mémoire factuelle)
-- Stocke les faits persistants et les consentements HITL (ALLOW_PROJECT / ALLOW_ALWAYS).
-- ADR-009 : C3+C6 fusionnées via scope. ADR-013 : tenant présent, figé à "default".
-- ADR-014 : SQLite pour démarrer.
-- =============================================================================

CREATE TABLE memory_entry (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_key  TEXT    NOT NULL,
    value      TEXT    NOT NULL,
    scope      TEXT    NOT NULL DEFAULT 'global',
    tenant     TEXT    NOT NULL DEFAULT 'default',
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,

    CONSTRAINT uq_memory_entry_key_tenant UNIQUE (entry_key, tenant)
);

CREATE INDEX idx_memory_entry_scope_tenant ON memory_entry (scope, tenant);
