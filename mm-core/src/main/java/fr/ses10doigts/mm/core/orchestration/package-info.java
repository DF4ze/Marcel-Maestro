/**
 * Orchestration inter-agents (étape 7 — orchestrateur minimal).
 *
 * <p>Contient les contrats ({@link fr.ses10doigts.mm.core.orchestration.AgentFactory},
 * {@link fr.ses10doigts.mm.core.orchestration.Dispatcher}) et la logique de routage
 * non-LLM. Le Dispatcher est le régisseur : il poll la file, instancie les agents
 * et route les résultats. Il n'est <em>pas</em> un agent.</p>
 */
package fr.ses10doigts.mm.core.orchestration;
