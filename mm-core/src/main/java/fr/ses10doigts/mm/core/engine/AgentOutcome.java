package fr.ses10doigts.mm.core.engine;

import fr.ses10doigts.mm.core.agent.AgentResponse;
import fr.ses10doigts.mm.core.agent.AgentStatus;

/**
 * Résultat final d'une exécution de {@link AgentLoop} (étape 3).
 *
 * <p>Toujours l'un des statuts terminaux du point de vue de la boucle : {@code DONE},
 * {@code KO} ou {@code BLOCKED} (attente humaine, étape 4). {@code lastResponse} est la
 * dernière {@link AgentResponse} valide reçue, ou {@code null} si aucune n'a pu être
 * parsée (échec de format jusqu'au {@code KO}).</p>
 *
 * @param finalStatus       statut terminal de la boucle
 * @param lastResponse      dernière réponse valide du LLM, ou {@code null}
 * @param iterations        nombre d'itérations (appels LLM) consommées
 * @param terminationReason raison lisible de la terminaison (pour journal et notification)
 */
public record AgentOutcome(
        AgentStatus finalStatus,
        AgentResponse lastResponse,
        int iterations,
        String terminationReason) {

    /** {@code true} si la boucle s'est terminée en succès. */
    public boolean isDone() {
        return finalStatus == AgentStatus.DONE;
    }
}
