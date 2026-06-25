package fr.ses10doigts.mm.starter.project;

/**
 * Cycle de vie d'un projet Marcel Maestro (ADR-022, §2.2).
 *
 * <ul>
 *   <li>{@link #ACTIVE} — reçoit de nouvelles conversations et tâches.</li>
 *   <li>{@link #ARCHIVED} — consultable en lecture (historique, mémoire),
 *       plus de nouvelles tâches.</li>
 * </ul>
 *
 * <p>La suppression est irréversible et n'est pas représentée par un statut :
 * elle retire la ligne de la table {@code project} (et cascade sur
 * {@code project_workspace}).</p>
 */
public enum ProjectStatus {
    ACTIVE,
    ARCHIVED
}
