package fr.ses10doigts.mm.app.specialist.coding;

import lombok.Builder;
import lombok.Getter;

/**
 * Décrit une tâche atomique déléguée à un agent spécialiste de coding.
 */
@Builder
@Getter
public class AgentTask {

    private final String id;
    private final String title;
    private final String description;
    private final TaskCategory category;
}
