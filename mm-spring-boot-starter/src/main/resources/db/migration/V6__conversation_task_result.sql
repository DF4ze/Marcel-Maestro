-- Enrichissement du lien conversation -> tache : routage et resultat (fermeture de boucle).
-- Permet d'enregistrer l'agent resolu, la categorie, le resume du resultat et l'horodatage de fin.

ALTER TABLE conversation_task ADD COLUMN agent_id TEXT;
ALTER TABLE conversation_task ADD COLUMN category TEXT;
ALTER TABLE conversation_task ADD COLUMN result_summary TEXT;
ALTER TABLE conversation_task ADD COLUMN completed_at TEXT;
