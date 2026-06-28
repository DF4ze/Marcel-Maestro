package fr.ses10doigts.mm.app.specialist.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Point d'entrée asynchrone technique du sous-système de coding agents.
 *
 * <p>Ce composant sert aux validations directes des spécialistes CLI et au endpoint
 * technique associé. Le chemin métier nominal reste
 * {@code TaskQueue -> Dispatcher -> Cortex -> spécialistes}.</p>
 */
@Service
@Slf4j
public class TaskDispatcher {

    private final TaskRouter taskRouter;
    private final Executor executor;
    private final Map<String, RunningTask> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, AgentReport> completedReports = new ConcurrentHashMap<>();

    @Autowired
    public TaskDispatcher(TaskRouter taskRouter) {
        this(taskRouter, Executors.newVirtualThreadPerTaskExecutor());
    }

    TaskDispatcher(TaskRouter taskRouter, Executor executor) {
        this.taskRouter = taskRouter;
        this.executor = executor;
    }

    /**
     * Soumet une tâche en utilisant un contexte Marcel minimal basé sur le répertoire courant.
     *
     * @param task tâche à router puis exécuter
     * @return identifiant de suivi de la tâche
     */
    public String submit(AgentTask task) {
        MarcelContext defaultContext = MarcelContext.builder()
                .projectMd("")
                .roadmapResultMd("")
                .c3Facts(List.of())
                .workingDirectory(Path.of("").toAbsolutePath().normalize().toString())
                .build();
        return submit(task, defaultContext);
    }

    /**
     * Soumet une tâche avec son contexte complet pour exécution asynchrone.
     *
     * @param task tâche à router puis exécuter
     * @param context contexte projet, mémoire et working directory à injecter
     * @return identifiant de suivi de la tâche
     */
    public String submit(AgentTask task, MarcelContext context) {
        String taskId = task.getId();
        log.info("TaskDispatcher submit - taskId={}, category={}", taskId, task.getCategory());

        RunningTask runningTask = new RunningTask();
        RunningTask previous = runningTasks.putIfAbsent(taskId, runningTask);
        if (previous != null) {
            throw new IllegalStateException("Une tâche avec l'id '" + taskId + "' est déjà en cours");
        }

        CompletableFuture<AgentReport> future = CompletableFuture.supplyAsync(() -> execute(task, context), executor);
        runningTask.setFuture(future);
        future.whenComplete((report, error) -> finalizeTask(taskId, report, error));
        return taskId;
    }

    /**
     * Annule une tâche en cours et enregistre un rapport KO déterministe si l'arrêt prend effet.
     *
     * @param taskId identifiant de la tâche à interrompre
     */
    public void stop(String taskId) {
        RunningTask runningTask = runningTasks.remove(taskId);
        if (runningTask == null) {
            log.info("TaskDispatcher stop - taskId={}, running=false", taskId);
            return;
        }

        CompletableFuture<AgentReport> future = runningTask.getFuture();
        boolean cancelled = future != null && future.cancel(true);
        completedReports.put(taskId, AgentReport.ko("Tâche interrompue par STOP"));
        log.info("TaskDispatcher stop - taskId={}, cancelled={}", taskId, cancelled);
    }

    /**
     * Retourne le dernier rapport connu pour une tâche.
     *
     * @param taskId identifiant de suivi
     * @return rapport si la tâche est terminée ou interrompue
     */
    public Optional<AgentReport> getReport(String taskId) {
        return Optional.ofNullable(completedReports.get(taskId));
    }

    /**
     * Indique si une tâche est encore en cours d'exécution.
     *
     * @param taskId identifiant de suivi
     * @return vrai si la tâche est encore active
     */
    public boolean isRunning(String taskId) {
        return runningTasks.containsKey(taskId);
    }

    /**
     * Exécute la mission sur le spécialiste résolu par le routeur technique.
     *
     * @param task tâche à exécuter
     * @param context contexte injecté au spécialiste
     * @return rapport structuré du spécialiste
     */
    private AgentReport execute(AgentTask task, MarcelContext context) {
        SpecialistAgentPort agent = taskRouter.resolve(task.getCategory());
        return agent.execute(task, context);
    }

    /**
     * Finalise l'état interne d'une tâche asynchrone technique.
     *
     * @param taskId identifiant de suivi
     * @param report rapport produit quand l'exécution réussit
     * @param error exception éventuelle remontée par la future
     */
    private void finalizeTask(String taskId, AgentReport report, Throwable error) {
        runningTasks.remove(taskId);
        if (error == null) {
            completedReports.put(taskId, report);
            log.info("TaskDispatcher termine - taskId={}, status={}", taskId, report.getStatus());
            return;
        }

        if (error instanceof java.util.concurrent.CancellationException) {
            log.info("TaskDispatcher termine - taskId={}, status={}", taskId, AgentReport.Status.KO);
            return;
        }

        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        AgentReport failure = AgentReport.ko("Echec d'execution : " + cause.getMessage());
        completedReports.put(taskId, failure);
        log.info("TaskDispatcher termine - taskId={}, status={}", taskId, failure.getStatus());
    }

    @Getter
    private static class RunningTask {
        private CompletableFuture<AgentReport> future;

        /**
         * Associe la future réellement soumise à ce suivi de tâche.
         *
         * @param future future d'exécution asynchrone
         */
        private void setFuture(CompletableFuture<AgentReport> future) {
            this.future = future;
        }
    }
}
