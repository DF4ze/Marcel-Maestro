package fr.ses10doigts.mm.app.rest;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de pilotage des tâches Marcel Maestro (étape 8, ADR-017).
 *
 * <p>Expose les endpoints CRUD sur les tâches : soumission, listing,
 * statut individuel et arrêt. Le {@link Dispatcher} est injecté via
 * {@link ObjectProvider} car il peut être absent si l'orchestrateur
 * n'est pas câblé.</p>
 */
@RestController
@RequestMapping("/api/tasks")
@Slf4j
@RequiredArgsConstructor
public class TaskController {

    private final ObjectProvider<Dispatcher> dispatcherProvider;
    private final TaskQueue taskQueue;

    // ── DTOs (inner records) ─────────────────────────────────────────────

    /**
     * Corps de la requête de soumission d'une tâche.
     *
     * <p>{@code projectId} et {@code conversationId} sont optionnels pour compatibilité
     * ascendante : s'ils sont absents du JSON, ils valent {@code null} et le moteur
     * utilise le contexte par défaut {@code "none"}. Pour que le bypass HITL write
     * (ADR-023, E2-M3) se déclenche, {@code projectId} doit correspondre à un projet
     * ayant au moins un workspace externe déclaré.</p>
     */
    public record TaskSubmitRequest(String content, String projectId, String conversationId) {}

    /** Réponse à une soumission de tâche. */
    public record TaskSubmitResponse(String taskId) {}

    /** Réponse listant les tâches actives et la taille de la file. */
    public record TaskListResponse(Set<String> activeTasks, int queueSize) {}

    /** Réponse de statut d'une tâche individuelle. */
    public record TaskStatusResponse(String taskId, boolean active) {}

    /** Réponse à une demande d'arrêt de tâche. */
    public record TaskStopResponse(String taskId, boolean stopped) {}

    // ── Endpoints ────────────────────────────────────────────────────────

    /**
     * Soumet une nouvelle tâche utilisateur dans la file.
     *
     * <p>Si {@code projectId} est fourni, il est propagé dans l'{@link AgentContext} :
     * le {@link fr.ses10doigts.mm.core.tool.ToolExecutionGuard} pourra alors bypasser
     * le HITL write pour les chemins dans un workspace externe déclaré (ADR-023, E2-M3).
     * Sans {@code projectId}, le comportement HITL est inchangé.</p>
     *
     * @param request contenu de la tâche, avec {@code projectId} et {@code conversationId} optionnels
     * @return identifiant de la tâche créée (HTTP 202)
     */
    @PostMapping
    public ResponseEntity<TaskSubmitResponse> submit(@RequestBody TaskSubmitRequest request) {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            log.info("POST /api/tasks — Dispatcher absent, renvoi 503");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        String taskId = UUID.randomUUID().toString();
        TaskMessage message = new TaskMessage(
                taskId,
                TaskType.USER_REQUEST,
                "cortex",
                request.content(),
                AgentContext.of(
                        "default",
                        request.projectId() != null ? request.projectId() : "none",
                        request.conversationId() != null ? request.conversationId() : "none",
                        taskId)
        );

        taskQueue.submit(message);
        log.info("POST /api/tasks — tâche soumise, taskId={}", taskId);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TaskSubmitResponse(taskId));
    }

    /**
     * Liste les tâches actives et la taille de la file d'attente.
     *
     * @return tâches actives et taille de la file
     */
    @GetMapping
    public ResponseEntity<TaskListResponse> list() {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            log.info("GET /api/tasks — Dispatcher absent, renvoi 503");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        Set<String> activeTasks = dispatcher.listActiveTaskIds();
        int queueSize = taskQueue.size();
        log.info("GET /api/tasks — {} tâche(s) active(s), {} en file", activeTasks.size(), queueSize);

        return ResponseEntity.ok(new TaskListResponse(activeTasks, queueSize));
    }

    /**
     * Retourne le statut d'une tâche individuelle.
     *
     * @param taskId identifiant de la tâche
     * @return statut de la tâche (active ou non)
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskStatusResponse> status(@PathVariable String taskId) {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            log.info("GET /api/tasks/{} — Dispatcher absent, renvoi 503", taskId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        boolean active = dispatcher.listActiveTaskIds().contains(taskId);
        log.info("GET /api/tasks/{} — active={}", taskId, active);

        return ResponseEntity.ok(new TaskStatusResponse(taskId, active));
    }

    /**
     * Arrête une tâche en cours d'exécution.
     *
     * @param taskId identifiant de la tâche à arrêter
     * @return résultat de l'arrêt
     */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<TaskStopResponse> stop(@PathVariable String taskId) {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            log.info("DELETE /api/tasks/{} — Dispatcher absent, renvoi 503", taskId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        boolean stopped = dispatcher.stop(taskId);
        log.info("DELETE /api/tasks/{} — stopped={}", taskId, stopped);

        return ResponseEntity.ok(new TaskStopResponse(taskId, stopped));
    }
}
