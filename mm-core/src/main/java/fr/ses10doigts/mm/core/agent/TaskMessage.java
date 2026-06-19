package fr.ses10doigts.mm.core.agent;

/**
 * Unité de communication inter-agents (ADR-008).
 *
 * <p>Toute communication entre agents transite par des {@code TaskMessage} typés
 * dans la file de tâches : zéro dialogue LLM↔LLM direct. La file elle-même et le
 * Dispatcher qui la consomme appartiennent à l'étape 7.</p>
 *
 * @param taskId   UUID de la tâche
 * @param type     type de message (pilote le routage par le Dispatcher)
 * @param assignee identifiant de l'agent destinataire
 * @param content  charge utile textuelle (requête, rapport…)
 * @param ctx      contexte d'exécution propagé
 */
public record TaskMessage(
        String taskId,
        TaskType type,
        String assignee,
        String content,
        AgentContext ctx) {
}
