package fr.ses10doigts.mm.app.specialist.coding;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.StopSignal;
import fr.ses10doigts.mm.core.orchestration.AgentFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base commune des adapters branchant Claude/Codex sur le Dispatcher historique.
 */
@RequiredArgsConstructor
@Slf4j
public abstract class AbstractCodingAgentFactoryAdapter implements AgentFactory {

    private final SpecialistAgentPort specialistAgent;
    private final TaskMessageCodingMissionMapper missionMapper;
    private final CodingAgentOutcomeMapper outcomeMapper;

    /**
     * Exécute la mission specialist.coding correspondant au {@code TaskMessage} reçu.
     *
     * @param task message de délégation du Dispatcher
     * @param stop signal d'arrêt coopératif ; non supporté par les CLI actuels
     * @return outcome terminal compatible Marcel
     */
    @Override
    public AgentOutcome execute(TaskMessage task, StopSignal stop) {
        if (stop.isStopped()) {
            return outcomeMapper.toOutcome(AgentReport.ko("Tâche interrompue avant démarrage"));
        }

        TaskMessageCodingMissionMapper.CodingMission mission = missionMapper.map(task, category());
        log.info("{} démarré via Dispatcher — taskId={}, projectId={}",
                agentId(), task.taskId(), task.ctx().projectId());

        AgentReport report = specialistAgent.execute(mission.task(), mission.context());
        AgentOutcome outcome = outcomeMapper.toOutcome(report);

        log.info("{} terminé via Dispatcher — taskId={}, reportStatus={}, finalStatus={}",
                agentId(), task.taskId(), report.getStatus(), outcome.finalStatus().json());
        return outcome;
    }

    /**
     * Retourne la catégorie métier de repli utilisée pour construire la mission.
     *
     * @return catégorie associée à l'adapter
     */
    protected abstract TaskCategory category();
}
