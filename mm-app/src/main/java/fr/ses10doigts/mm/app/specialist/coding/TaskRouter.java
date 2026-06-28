package fr.ses10doigts.mm.app.specialist.coding;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Résout l'agent spécialiste à utiliser selon la catégorie métier de la tâche.
 */
@Component
@RequiredArgsConstructor
public class TaskRouter {

    private final Map<String, SpecialistAgentPort> specialistAgents;
    private final CodingAgentsProperties properties;

    /**
     * Retourne l'agent spécialiste configuré pour la catégorie demandée.
     *
     * @param category catégorie métier de la tâche
     * @return implémentation de {@link SpecialistAgentPort} à utiliser
     */
    public SpecialistAgentPort resolve(TaskCategory category) {
        TaskCategory effectiveCategory = category == null ? TaskCategory.CODING : category;
        String agentId = properties.getRouting().get(effectiveCategory);
        SpecialistAgentPort agent = specialistAgents.get(agentId);
        if (agent == null) {
            throw new IllegalStateException("Aucun agent spécialiste configuré pour " + effectiveCategory
                    + " via l'identifiant '" + agentId + "'");
        }
        return agent;
    }
}
