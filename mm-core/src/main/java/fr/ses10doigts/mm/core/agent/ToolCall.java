package fr.ses10doigts.mm.core.agent;

import java.util.Map;

/**
 * Demande d'appel d'outil émise par le LLM dans {@link AgentResponse}.
 *
 * @param tool   nom de l'outil ({@code AgentTool.name()}, snake_case)
 * @param params paramètres à passer à l'outil (structure libre, validée à l'exécution)
 */
public record ToolCall(String tool, Map<String, Object> params) {
}
