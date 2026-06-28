package fr.ses10doigts.mm.app.specialist.coding;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Convertit un {@link TaskMessage} du moteur historique en mission specialist.coding.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskMessageCodingMissionMapper {

    private final CodingMissionContextBuilder contextBuilder;

    /**
     * Reconstruit la mission métier attendue par Claude/Codex.
     *
     * @param task message de délégation reçu du Dispatcher
     * @param defaultCategory catégorie métier de repli associée à l'adapter
     * @return mission complète compatible avec le sous-système coding
     */
    public CodingMission map(TaskMessage task, TaskCategory defaultCategory) {
        AgentTask agentTask = AgentTask.builder()
                .id(task.taskId())
                .title(buildTitle(task, defaultCategory))
                .description(task.content())
                .category(defaultCategory)
                .build();
        MarcelContext context = contextBuilder.build(task.ctx());

        log.debug("TaskMessageCodingMissionMapper — taskId={}, assignee={}, category={}",
                task.taskId(), task.assignee(), defaultCategory);
        return new CodingMission(agentTask, context);
    }

    /**
     * Standardise un titre lisible pour les spécialistes CLI.
     *
     * @param task message de départ
     * @param defaultCategory catégorie métier portée par l'adapter
     * @return titre synthétique de mission
     */
    private String buildTitle(TaskMessage task, TaskCategory defaultCategory) {
        String content = task.content() == null ? "" : task.content().trim();
        if (content.isBlank()) {
            return "Mission " + defaultCategory.name();
        }
        return content.length() <= 80 ? content : content.substring(0, 80) + "...";
    }

    /**
     * Mission complète dérivée d'un {@link TaskMessage}.
     *
     * @param task tâche métier pour l'agent CLI
     * @param context contexte projet et mémoire injecté au brief
     */
    public record CodingMission(AgentTask task, MarcelContext context) {
    }
}
