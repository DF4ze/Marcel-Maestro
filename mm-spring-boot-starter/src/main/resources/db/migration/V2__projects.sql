-- =============================================================================
-- V2 — Tables projet (E2-M1)
-- ADR-022 : source de vérité DB (pas file-driven). ADR-014 : même datasource SQLite.
-- IDs en UUID (TEXT), timestamps en ISO-8601 (TEXT, compatible SQLite).
-- ON DELETE CASCADE sur project_workspace : la suppression d'un projet nettoie
-- automatiquement ses dossiers externes en base (le filesystem est nettoyé par
-- ProjectService, côté applicatif).
-- Le champ config (TEXT JSON, nullable) est une couture pour de futurs paramètres
-- (description, tech stack…) — sans logique associée en E2-M1 (§8 points ouverts).
-- =============================================================================

CREATE TABLE project (
    id              TEXT PRIMARY KEY,           -- UUID v4
    name            TEXT NOT NULL,              -- nom d'affichage original (ex: "Mon Projet")
    sanitized_name  TEXT NOT NULL UNIQUE,       -- slug kebab-case, clé du dossier workspace
    workspace_path  TEXT NOT NULL,              -- chemin absolu du dossier interne
    status          TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | ARCHIVED
    config          TEXT,                       -- JSON nullable, couture futurs paramètres
    created_at      TEXT NOT NULL,              -- ISO-8601 (ex: 2026-06-24T10:00:00Z)
    updated_at      TEXT NOT NULL               -- ISO-8601
);

-- Index sur status pour les requêtes de liste filtrée (GET /projects?status=ACTIVE).
CREATE INDEX idx_project_status ON project (status);

CREATE TABLE project_workspace (
    id          TEXT PRIMARY KEY,           -- UUID v4
    project_id  TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    path        TEXT NOT NULL,              -- chemin absolu du dossier externe déclaré
    added_at    TEXT NOT NULL               -- ISO-8601
);

-- Index pour le chargement des workspaces d'un projet (addWorkspace, PathValidator futur).
CREATE INDEX idx_project_workspace_project ON project_workspace (project_id);
