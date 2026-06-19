/**
 * Types pivots du noyau agentique.
 *
 * <p>Contrats de données échangés par le moteur : contexte d'exécution
 * ({@link fr.ses10doigts.mm.core.agent.AgentContext}), statut d'agent
 * ({@link fr.ses10doigts.mm.core.agent.AgentStatus}), sortie structurée
 * ({@link fr.ses10doigts.mm.core.agent.AgentResponse}) et message inter-agents
 * ({@link fr.ses10doigts.mm.core.agent.TaskMessage}).</p>
 *
 * <p><strong>Aucune logique ici</strong> — uniquement des records/enums immuables.
 * La sortie JSON {@code AgentResponse} <em>est</em> la machine à états (ADR-006) :
 * le parsing est déterministe (étape 3), jamais d'interprétation NLP.</p>
 */
package fr.ses10doigts.mm.core.agent;
