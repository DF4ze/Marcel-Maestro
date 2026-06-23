package fr.ses10doigts.mm.app.specialist;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.AgentLoop;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.StopSignal;
import fr.ses10doigts.mm.core.orchestration.AgentFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent Cortex — orchestrateur principal de Marcel Maestro.
 *
 * <p>Point d'entrée de toutes les requêtes utilisateur ({@code USER_REQUEST}) et
 * des rapports de spécialistes ({@code SPECIALIST_REPORT}). Délègue au
 * {@link AgentLoop} configuré par le starter (LLM, outils, HITL, mémoire).</p>
 *
 * <p>L'{@link AgentLoop} est thread-safe et réutilisable : une seule instance
 * est injectée et partagée entre les exécutions parallèles.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class CortexFactory implements AgentFactory {

    /** Identifiant de l'agent cortex, utilisé comme {@code assignee} dans les TaskMessage. */
    public static final String AGENT_ID = "cortex";

    private final AgentLoop agentLoop;

    /**
     * {@inheritDoc}
     *
     * @return {@code "cortex"}
     */
    @Override
    public String agentId() {
        return AGENT_ID;
    }

    /**
     * Exécute la tâche via la boucle agentique principale.
     *
     * @param task tâche à traiter (USER_REQUEST ou SPECIALIST_REPORT)
     * @param stop signal d'arrêt coopératif
     * @return résultat de l'exécution
     */
    @Override
    public AgentOutcome execute(TaskMessage task, StopSignal stop) {
        log.info("Cortex démarré — taskId={}, type={}, contenu='{}'",
                task.taskId(), task.type(), truncate(task.content(), 80));

        AgentOutcome outcome = agentLoop.run(task, stop);

        log.info("Cortex terminé — taskId={}, status={}, iterations={}",
                task.taskId(), outcome.finalStatus().json(), outcome.iterations());
        return outcome;
    }

    /**
     * Tronque un texte pour le logging.
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
