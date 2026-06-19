package fr.ses10doigts.mm.core.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Contrat de sortie structurée imposé à tout agent — la sortie JSON
 * <em>est</em> la machine à états (ADR-006).
 *
 * <p>Forme JSON attendue :</p>
 * <pre>
 * {
 *   "status": "running|done|blocked|trouble|KO",
 *   "reason": "Description courte de la situation",
 *   "output": "Résultat si status=done (sinon null)",
 *   "tool_calls": [{"tool": "nom_outil", "params": {}}],
 *   "sub_tasks": [{"assignee": "specialist_id", "description": "..."}]
 * }
 * </pre>
 *
 * <p>Le parsing (déterministe, {@code ObjectMapper.readValue}) et son durcissement
 * (cas dégradés, retry) appartiennent à l'étape 3. Ici : la forme uniquement.</p>
 *
 * @param status    statut de l'agent (pilote le routage)
 * @param reason    description courte de la situation
 * @param output    résultat produit si {@link AgentStatus#DONE}, sinon {@code null}
 * @param toolCalls appels d'outils demandés (peut être vide ou {@code null})
 * @param subTasks  sous-tâches déléguées par le Cortex (peut être vide ou {@code null})
 */
public record AgentResponse(
        @JsonProperty("status") AgentStatus status,
        @JsonProperty("reason") String reason,
        @JsonProperty("output") String output,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("sub_tasks") List<SubTask> subTasks) {
}
