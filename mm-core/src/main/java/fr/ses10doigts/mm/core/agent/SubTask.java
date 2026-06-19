package fr.ses10doigts.mm.core.agent;

/**
 * Sous-tâche déléguée par le Cortex dans {@link AgentResponse}.
 *
 * <p>SSOT (ADR-007) : seul le Cortex produit des sous-tâches ; les spécialistes
 * exécutent et n'en créent jamais.</p>
 *
 * @param assignee    identifiant du spécialiste destinataire
 * @param description description de la sous-tâche à réaliser
 */
public record SubTask(String assignee, String description) {
}
