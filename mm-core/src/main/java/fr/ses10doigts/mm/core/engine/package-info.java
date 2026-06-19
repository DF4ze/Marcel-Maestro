/**
 * Boucle agentique de Cortex : fiable et bornée (étape 3).
 *
 * <p>Le cœur du « gros périmètre de façon fiable ». La sortie JSON structurée
 * <em>est</em> la machine à états (ADR-006) : {@link
 * fr.ses10doigts.mm.core.engine.AgentStateMachine} route sur {@link
 * fr.ses10doigts.mm.core.agent.AgentStatus} via un {@code switch} exhaustif, {@link
 * fr.ses10doigts.mm.core.engine.LoopGuards} borne la boucle et détecte les boucles
 * infinies, {@link fr.ses10doigts.mm.core.engine.StopSignal} permet un STOP propre, et
 * {@link fr.ses10doigts.mm.core.engine.AgentLoop} orchestre le tout autour du {@code
 * ChatClient} Spring AI <strong>injecté</strong>.</p>
 *
 * <p>Discipline : déterministe en bas (parsing, routage, bornes), LLM pour le jugement
 * seulement. Le noyau reste pur — aucun bean concret de LLM n'y est créé.</p>
 */
package fr.ses10doigts.mm.core.engine;
