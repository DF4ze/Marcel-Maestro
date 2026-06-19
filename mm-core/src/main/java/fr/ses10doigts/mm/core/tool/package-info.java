/**
 * SPI des outils enfichables (ADR-004).
 *
 * <p>L'hôte n'implémente que {@link fr.ses10doigts.mm.core.tool.AgentTool}. Le moteur
 * l'adapte vers {@code FunctionCallback} de Spring AI via un converter interne
 * (étape 6) : on ne réenrobe ni le JSON Schema ni la sérialisation. La seule valeur
 * ajoutée du contrat est {@link fr.ses10doigts.mm.core.tool.RiskLevel}, qui pilote le
 * garde-fou humain (HITL).</p>
 *
 * <p>Aucun outil réel ici (étape 6). Uniquement le contrat.</p>
 */
package fr.ses10doigts.mm.core.tool;
