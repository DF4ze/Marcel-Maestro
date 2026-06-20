package fr.ses10doigts.mm.core.orchestration;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.SubTask;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.StopSignal;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatcher — composant permanent non-LLM de routage inter-agents (étape 7).
 *
 * <p>Poll la {@link TaskQueue}, instancie l'agent assigné via la factory correspondante,
 * le lance dans le pool borné, et route le résultat. Il gère également la délégation
 * Cortex → sous-tâches : quand un agent retourne un {@code AgentOutcome} contenant des
 * {@code sub_tasks}, le Dispatcher les convertit en {@link TaskMessage} de type
 * {@code SPECIALIST_REQUEST} et les soumet dans la file.</p>
 *
 * <p>Le Dispatcher n'est <em>pas</em> un agent : il ne fait aucun appel LLM. Sa boucle
 * de poll tourne dans un thread dédié (pas dans le pool de travail). L'arrêt propre
 * est géré via {@link #shutdown()} (arrêt de la boucle) et {@link #stop(String)}
 * (arrêt d'une tâche individuelle).</p>
 *
 * <p>POJO pur dans {@code mm-core}. Le câblage Spring ({@code @PostConstruct},
 * {@code ThreadPoolTaskExecutor}) vit dans le starter.</p>
 */
@Slf4j
public class Dispatcher {

    private final TaskQueue taskQueue;
    private final Map<String, AgentFactory> agentFactories;
    private final Executor executor;
    private final ConcurrentHashMap<String, DispatcherHandle> activeHandles = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Construit un Dispatcher.
     *
     * @param taskQueue      file de tâches à consommer
     * @param agentFactories liste des factories d'agents disponibles
     * @param executor       pool borné pour l'exécution des agents
     */
    public Dispatcher(TaskQueue taskQueue, List<AgentFactory> agentFactories, Executor executor) {
        this.taskQueue = taskQueue;
        this.agentFactories = agentFactories.stream()
                .collect(Collectors.toMap(AgentFactory::agentId, Function.identity()));
        this.executor = executor;
        log.info("Dispatcher initialisé — {} factory(ies) enregistrée(s) : {}",
                this.agentFactories.size(), this.agentFactories.keySet());
    }

    /**
     * Démarre la boucle de poll dans un thread dédié. Idempotent.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            Thread pollThread = new Thread(this::pollLoop, "mm-dispatcher-poll");
            pollThread.setDaemon(true);
            pollThread.start();
            log.info("Dispatcher démarré — boucle de poll active");
        }
    }

    /**
     * Arrête la boucle de poll. Les agents en cours continuent jusqu'à leur terminaison
     * naturelle ou un appel explicite à {@link #stop(String)}.
     */
    public void shutdown() {
        running.set(false);
        log.info("Dispatcher en arrêt — la boucle de poll va s'interrompre");
    }

    /**
     * Arrête proprement une tâche en cours d'exécution.
     *
     * <p>Positionne le {@link StopSignal} de l'agent (arrêt coopératif entre
     * opérations atomiques) et nettoie la file des sous-tâches éventuellement
     * en attente pour ce {@code taskId}.</p>
     *
     * @param taskId identifiant de la tâche à arrêter
     * @return {@code true} si la tâche était active et le signal a été envoyé
     */
    public boolean stop(String taskId) {
        DispatcherHandle handle = activeHandles.get(taskId);
        boolean queueCleaned = taskQueue.remove(taskId);

        if (handle != null) {
            handle.stop().stop();
            log.info("STOP envoyé à la tâche en cours — taskId={}", taskId);
            return true;
        }

        if (queueCleaned) {
            log.info("Tâche retirée de la file (pas encore démarrée) — taskId={}", taskId);
            // Produire un rapport KO pour cette tâche stoppée
            taskQueue.submit(new TaskMessage(
                    taskId, TaskType.SPECIALIST_REPORT, "cortex",
                    "Tâche annulée avant démarrage (stopped by user)",
                    AgentContext.of("default", "none", "none", taskId)));
            return true;
        }

        log.info("Tâche introuvable pour STOP — taskId={}", taskId);
        return false;
    }

    /**
     * Indique si la boucle de poll est active.
     *
     * @return {@code true} si le Dispatcher tourne
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Nombre de tâches actuellement en cours d'exécution dans le pool.
     *
     * @return nombre de handles actifs
     */
    public int activeCount() {
        return activeHandles.size();
    }

    /**
     * Retourne les identifiants des tâches actuellement en cours d'exécution.
     *
     * @return ensemble non-modifiable des taskIds actifs
     */
    public Set<String> listActiveTaskIds() {
        return Set.copyOf(activeHandles.keySet());
    }

    /**
     * Boucle principale de poll. Tourne dans un thread dédié (daemon).
     * Dépile les {@link TaskMessage} et les soumet au pool d'exécution.
     */
    private void pollLoop() {
        log.info("Boucle de poll démarrée");
        while (running.get()) {
            try {
                TaskMessage task = taskQueue.poll(2, TimeUnit.SECONDS);
                if (task == null) {
                    continue;
                }
                dispatch(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Boucle de poll interrompue");
                break;
            }
        }
        log.info("Boucle de poll terminée");
    }

    /**
     * Route une tâche vers la factory correspondante et soumet l'exécution au pool.
     *
     * @param task le message à router
     */
    private void dispatch(TaskMessage task) {
        String assignee = task.assignee();
        AgentFactory factory = agentFactories.get(assignee);

        if (factory == null) {
            log.info("Aucune factory pour l'assignee '{}' — tâche ignorée (taskId={})",
                    assignee, task.taskId());
            // Produire un rapport KO si c'était une requête de spécialiste
            if (task.type() == TaskType.SPECIALIST_REQUEST) {
                taskQueue.submit(new TaskMessage(
                        task.taskId(), TaskType.SPECIALIST_REPORT, "cortex",
                        "Erreur : spécialiste '" + assignee + "' introuvable",
                        task.ctx()));
            }
            return;
        }

        log.info("Instanciation de l'agent '{}' pour taskId={}", assignee, task.taskId());

        StopSignal stop = new StopSignal();
        DispatcherHandle handle = new DispatcherHandle(task.taskId(), stop);
        activeHandles.put(task.taskId(), handle);

        executor.execute(() -> {
            try {
                AgentOutcome outcome = factory.execute(task, stop);
                log.info("Agent '{}' terminé — taskId={}, finalStatus={}, iterations={}",
                        assignee, task.taskId(), outcome.finalStatus().json(), outcome.iterations());

                handleOutcome(task, outcome);
            } catch (Exception e) {
                log.info("Exception non gérée dans l'agent '{}' — taskId={} : {}",
                        assignee, task.taskId(), e.getMessage());
            } finally {
                activeHandles.remove(task.taskId());
                log.debug("Handle retiré pour taskId={}", task.taskId());
            }
        });
    }

    /**
     * Traite le résultat d'un agent. Si l'agent a produit des {@code sub_tasks},
     * les convertit en {@link TaskMessage} et les soumet dans la file.
     * Si c'est un rapport de spécialiste, le route vers le Cortex.
     *
     * @param originalTask la tâche qui a produit ce résultat
     * @param outcome      le résultat de l'agent
     */
    private void handleOutcome(TaskMessage originalTask, AgentOutcome outcome) {
        // ── Cas 1 : sous-tâches produites par le Cortex ──────────────────
        if (hasSubTasks(outcome)) {
            List<SubTask> subTasks = outcome.lastResponse().subTasks();
            log.info("Cortex a produit {} sous-tâche(s) — routage vers les spécialistes",
                    subTasks.size());

            for (SubTask sub : subTasks) {
                String subTaskId = UUID.randomUUID().toString();
                AgentContext subCtx = AgentContext.of(
                        originalTask.ctx().tenant(),
                        originalTask.ctx().projectId(),
                        originalTask.ctx().conversationId(),
                        subTaskId);

                TaskMessage specialistRequest = new TaskMessage(
                        subTaskId,
                        TaskType.SPECIALIST_REQUEST,
                        sub.assignee(),
                        sub.description(),
                        subCtx);

                taskQueue.submit(specialistRequest);
                log.info("Sous-tâche soumise — subTaskId={}, assignee={}, description={}",
                        subTaskId, sub.assignee(), truncate(sub.description(), 80));
            }
            return;
        }

        // ── Cas 2 : rapport de spécialiste → retour vers le Cortex ───────
        if (originalTask.type() == TaskType.SPECIALIST_REQUEST) {
            String reportContent = buildReportContent(originalTask, outcome);
            TaskMessage report = new TaskMessage(
                    originalTask.taskId(),
                    TaskType.SPECIALIST_REPORT,
                    "cortex",
                    reportContent,
                    originalTask.ctx());

            taskQueue.submit(report);
            log.info("Rapport de spécialiste soumis pour Cortex — taskId={}, status={}",
                    originalTask.taskId(), outcome.finalStatus().json());
        }

        // ── Cas 3 : tâche utilisateur terminée sans sous-tâche → fin ─────
        if (originalTask.type() == TaskType.USER_REQUEST && !hasSubTasks(outcome)) {
            log.info("Tâche utilisateur terminée — taskId={}, status={}",
                    originalTask.taskId(), outcome.finalStatus().json());
        }
    }

    /**
     * Vérifie si un résultat contient des sous-tâches à déléguer.
     */
    private static boolean hasSubTasks(AgentOutcome outcome) {
        return outcome.lastResponse() != null
                && outcome.lastResponse().subTasks() != null
                && !outcome.lastResponse().subTasks().isEmpty()
                && outcome.finalStatus() == AgentStatus.DONE;
    }

    /**
     * Construit le contenu textuel du rapport d'un spécialiste.
     */
    private static String buildReportContent(TaskMessage originalTask, AgentOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("[RAPPORT du spécialiste '").append(originalTask.assignee()).append("']\n");
        sb.append("Statut: ").append(outcome.finalStatus().json()).append("\n");
        sb.append("Itérations: ").append(outcome.iterations()).append("\n");
        if (outcome.terminationReason() != null) {
            sb.append("Raison: ").append(outcome.terminationReason()).append("\n");
        }
        if (outcome.lastResponse() != null && outcome.lastResponse().output() != null) {
            sb.append("Résultat:\n").append(outcome.lastResponse().output());
        }
        return sb.toString();
    }

    /**
     * Tronque un texte pour le logging.
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
