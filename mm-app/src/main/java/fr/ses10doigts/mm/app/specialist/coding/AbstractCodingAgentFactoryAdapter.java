package fr.ses10doigts.mm.app.specialist.coding;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.StopSignal;
import fr.ses10doigts.mm.core.orchestration.AgentFactory;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base commune des adapters branchant Claude/Codex sur le Dispatcher historique.
 */
@RequiredArgsConstructor
@Slf4j
public abstract class AbstractCodingAgentFactoryAdapter implements AgentFactory {

    /** Intervalle de vérification du STOP pendant l'exécution du CLI. */
    private static final long STOP_POLL_INTERVAL_MS = 200L;

    private final SpecialistAgentPort specialistAgent;
    private final TaskMessageCodingMissionMapper missionMapper;
    private final CodingAgentOutcomeMapper outcomeMapper;

    /**
     * Exécute la mission specialist.coding correspondant au {@code TaskMessage} reçu.
     *
     * <p>Le CLI tourne dans un thread dédié pendant que ce thread surveille le
     * {@link StopSignal}. Sur STOP, le {@code Future} est annulé avec interruption : le thread
     * d'exécution est interrompu, ce qui fait lever {@code InterruptedException} dans
     * {@code CrossPlatformRunner} qui détruit alors le process CLI. La tâche se termine en KO
     * déterministe.</p>
     *
     * @param task message de délégation du Dispatcher
     * @param stop signal d'arrêt coopératif
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

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, agentId() + "-cli-" + task.taskId());
            thread.setDaemon(true);
            return thread;
        });
        Future<AgentReport> future =
                executor.submit(() -> specialistAgent.execute(mission.task(), mission.context()));
        try {
            while (true) {
                if (stop.isStopped()) {
                    future.cancel(true);
                    log.info("{} interrompu par STOP — taskId={}", agentId(), task.taskId());
                    return outcomeMapper.toOutcome(AgentReport.ko("Tâche interrompue par STOP"));
                }
                try {
                    AgentReport report = future.get(STOP_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    AgentOutcome outcome = outcomeMapper.toOutcome(report);
                    log.info("{} terminé via Dispatcher — taskId={}, reportStatus={}, finalStatus={}",
                            agentId(), task.taskId(), report.getStatus(), outcome.finalStatus().json());
                    return outcome;
                } catch (TimeoutException stillRunning) {
                    // CLI encore en cours : on reboucle pour re-vérifier le STOP.
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("{} échec via Dispatcher — taskId={} : {}", agentId(), task.taskId(), cause.getMessage());
            return outcomeMapper.toOutcome(AgentReport.ko("Echec d'exécution : " + cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.info("{} interrompu (thread) — taskId={}", agentId(), task.taskId());
            return outcomeMapper.toOutcome(AgentReport.ko("Tâche interrompue"));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Retourne la catégorie métier de repli utilisée pour construire la mission.
     *
     * @return catégorie associée à l'adapter
     */
    protected abstract TaskCategory category();
}
