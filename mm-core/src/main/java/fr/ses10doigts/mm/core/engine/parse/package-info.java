/**
 * Parsing déterministe de la sortie LLM en {@link fr.ses10doigts.mm.core.agent.AgentResponse}
 * et durcissement des cas dégradés (étape 3, livrable 3 ; PB-08, PB-09).
 *
 * <p>Principe : la sortie JSON <em>est</em> la machine à états (ADR-006). Le parsing est
 * déterministe (Jackson) avec des filets explicites (extraction regex du bloc JSON,
 * détection de troncature via {@code finishReason}). Aucune interprétation NLP du texte
 * libre : un JSON invalide est une erreur, traitée comme telle par la boucle (→ trouble).</p>
 */
package fr.ses10doigts.mm.core.engine.parse;
