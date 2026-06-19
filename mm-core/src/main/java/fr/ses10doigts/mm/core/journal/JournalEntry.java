package fr.ses10doigts.mm.core.journal;

import java.time.Instant;
import java.util.Map;

/**
 * Entrée de journal append-only (PB-04 Q2).
 *
 * <p><strong>Forme générique</strong> (validée à l'étape 2). Le sac {@code data}
 * accueille décisions, tool_calls, résultats et transitions sans figer un schéma trop
 * tôt. À spécialiser si l'audit l'exige. Voir note de report étape 3.</p>
 *
 * @param at       instant de l'événement
 * @param agentId  identifiant de l'agent émetteur
 * @param taskId   identifiant de la tâche
 * @param category catégorie d'événement (décision, tool_call, transition…)
 * @param data     détails structurés de l'événement
 */
public record JournalEntry(
        Instant at,
        String agentId,
        String taskId,
        String category,
        Map<String, Object> data) {
}
