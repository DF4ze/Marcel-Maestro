package fr.ses10doigts.mm.app.rest.dto;

import fr.ses10doigts.mm.app.specialist.coding.TaskCategory;
import java.util.List;

/**
 * Payload de soumission d'une mission au sous-système CodingAgentAdapter.
 *
 * @param title titre synthétique de la tâche
 * @param description description détaillée à envoyer au CLI
 * @param category catégorie de routage vers l'agent spécialiste
 * @param projectMd contenu projet injecté dans le brief
 * @param roadmapResultMd état de roadmap injecté dans le brief
 * @param c3Facts faits mémoire à injecter
 * @param workingDirectory répertoire de travail cible du CLI
 */
public record CodingTaskSubmitRequest(
        String title,
        String description,
        TaskCategory category,
        String projectMd,
        String roadmapResultMd,
        List<String> c3Facts,
        String workingDirectory) {
}
