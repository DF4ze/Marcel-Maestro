package fr.ses10doigts.mm.app.rest;

import fr.ses10doigts.mm.app.rest.dto.CodingTaskSubmitRequest;
import fr.ses10doigts.mm.app.specialist.coding.AgentTask;
import fr.ses10doigts.mm.app.specialist.coding.MarcelContext;
import fr.ses10doigts.mm.app.specialist.coding.TaskCategory;
import fr.ses10doigts.mm.app.specialist.coding.TaskDispatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST minimale dédiée au CodingAgentAdapter.
 */
@RestController
@RequestMapping("/api/coding-agent-tasks")
@RequiredArgsConstructor
@Slf4j
public class CodingTaskController {

    private final TaskDispatcher taskDispatcher;

    /**
     * Soumet une mission de coding agent pour exécution asynchrone.
     *
     * @param request corps de mission incluant le contexte minimal nécessaire au CLI
     * @return identifiant de suivi de la tâche
     */
    @PostMapping
    public ResponseEntity<TaskSubmitResponse> submit(@RequestBody CodingTaskSubmitRequest request) {
        String taskId = UUID.randomUUID().toString();
        AgentTask task = AgentTask.builder()
                .id(taskId)
                .title(request.title())
                .description(request.description())
                .category(request.category() == null ? TaskCategory.CODING : request.category())
                .build();
        MarcelContext context = MarcelContext.builder()
                .projectMd(request.projectMd())
                .roadmapResultMd(request.roadmapResultMd())
                .c3Facts(request.c3Facts() == null ? List.of() : request.c3Facts())
                .workingDirectory(resolveWorkingDirectory(request.workingDirectory()))
                .build();

        log.info("POST /api/coding-agent-tasks — taskId={}, category={}", taskId, task.getCategory());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TaskSubmitResponse(taskDispatcher.submit(task, context)));
    }

    /**
     * Annule une mission encore active.
     *
     * @param taskId identifiant de suivi
     * @return réponse vide une fois la demande d'arrêt propagée
     */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> stop(@PathVariable String taskId) {
        taskDispatcher.stop(taskId);
        log.info("DELETE /api/coding-agent-tasks/{} — stop demandé", taskId);
        return ResponseEntity.noContent().build();
    }

    private String resolveWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return Path.of("").toAbsolutePath().normalize().toString();
        }
        return workingDirectory;
    }

    /**
     * Réponse minimaliste de soumission asynchrone.
     *
     * @param taskId identifiant de suivi renvoyé au client
     */
    public record TaskSubmitResponse(String taskId) {
    }
}
