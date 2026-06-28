package fr.ses10doigts.mm.app.specialist.coding;

import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import java.util.Map;

/**
 * Injecte dans le prompt Cortex les règles de délégation vers Claude/Codex.
 */
public class CodingRoutingPromptExtension implements SystemPromptExtension {

    private final Map<TaskCategory, String> routing;

    /**
     * Capture la configuration de routage afin de l'exposer explicitement à Cortex.
     *
     * @param properties propriétés applicatives des spécialistes coding
     */
    public CodingRoutingPromptExtension(CodingAgentsProperties properties) {
        this.routing = Map.copyOf(properties.getRouting());
    }

    /**
     * Décrit le chemin nominal de délégation et les assignees autorisés.
     *
     * @return contribution textuelle ajoutée au system prompt de Cortex
     */
    @Override
    public String contribution() {
        return """
                RÈGLES DE DÉLÉGATION SPÉCIALISTES CODING
                - Le flux nominal est : USER_REQUEST -> cortex -> sub_tasks -> spécialiste -> SPECIALIST_REPORT -> cortex.
                - Tu restes l'orchestrateur unique. Les spécialistes ne planifient pas et ne créent jamais de nouvelles sub_tasks.
                - Si tu délègues une sous-tâche de développement, renseigne directement l'assignee final dans "sub_tasks".
                - Utilise exclusivement les assignees suivants pour les tâches coding :
                  * %s : code, refacto, bugfix, lecture de code, audit, analyse technique
                  * %s : build Maven, shell, scripts, CI, ops, exécution outillée
                - N'utilise pas de catégorie intermédiaire dans les sub_tasks. L'assignee final doit être explicite.
                - Si tu peux conclure sans délégation, réponds directement en JSON final sans créer de sub_task.
                """.formatted(
                routing.getOrDefault(TaskCategory.CODING, "claude"),
                routing.getOrDefault(TaskCategory.BUILD, "codex"));
    }
}
