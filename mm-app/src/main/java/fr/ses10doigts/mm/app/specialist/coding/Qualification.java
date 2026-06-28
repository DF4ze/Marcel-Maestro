package fr.ses10doigts.mm.app.specialist.coding;

/**
 * Résultat d'une qualification de tâche par le {@link TaskQualifier}.
 *
 * @param category catégorie métier retenue pour la tâche
 * @param agentId identifiant de l'agent spécialiste résolu ({@code claude}, {@code codex}…)
 * @param source origine de la décision (règles, LLM, défaut) — pour l'observabilité
 */
public record Qualification(TaskCategory category, String agentId, QualificationSource source) {
}
