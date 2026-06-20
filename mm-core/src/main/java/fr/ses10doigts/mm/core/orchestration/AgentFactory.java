package fr.ses10doigts.mm.core.orchestration;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.StopSignal;

/**
 * Fabrique et exécuteur d'un agent identifié par son {@link #agentId()}.
 *
 * <p>Chaque spécialiste (ou le Cortex) est déclaré comme un bean {@code AgentFactory}.
 * Le {@code Dispatcher} collecte toutes les factories et les indexe par {@link #agentId()} ;
 * lors du routage d'un {@link TaskMessage}, il appelle {@link #execute(TaskMessage, StopSignal)}
 * sur la factory correspondant au champ {@code assignee}.</p>
 *
 * <p>L'implémentation typique configure un {@code AgentLoop} avec le system prompt,
 * la liste blanche d'outils et les paramètres propres au spécialiste, puis appelle
 * {@code agentLoop.run(task, stop)}.</p>
 *
 * <p>Vit dans {@code mm-core} (contrat pur). Les implémentations concrètes vivent dans
 * le starter (Cortex par défaut) ou l'app (spécialistes métier).</p>
 */
public interface AgentFactory {

    /**
     * Identifiant unique de l'agent produit par cette factory.
     * Correspond au champ {@code assignee} des {@link TaskMessage}.
     *
     * @return identifiant stable, non-null, en kebab-case (ex. {@code "cortex"},
     *         {@code "echo-specialist"})
     */
    String agentId();

    /**
     * Exécute la tâche dans la boucle agentique de cet agent.
     *
     * <p>Appel bloquant : le thread courant est occupé jusqu'à la terminaison
     * (statut terminal ou STOP). Le {@code Dispatcher} soumet cet appel dans
     * un pool borné.</p>
     *
     * @param task tâche à traiter
     * @param stop signal d'arrêt coopératif
     * @return résultat final, jamais {@code null}
     */
    AgentOutcome execute(TaskMessage task, StopSignal stop);
}
