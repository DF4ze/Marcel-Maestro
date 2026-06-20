/**
 * SPI des outils enfichables et passerelle vers Spring AI (ADR-004, etape 6).
 *
 * <p>L'hote n'implemente que {@link fr.ses10doigts.mm.core.tool.AgentTool}. Le moteur
 * l'adapte vers {@link org.springframework.ai.tool.ToolCallback} via
 * {@link fr.ses10doigts.mm.core.tool.AgentToolConverter} : on ne reenrobe ni le
 * JSON Schema ni la serialisation. La seule valeur ajoutee du contrat est
 * {@link fr.ses10doigts.mm.core.tool.RiskLevel}, qui pilote le garde-fou humain
 * (HITL).</p>
 *
 * <p>Composants cles :</p>
 * <ul>
 *   <li>{@link fr.ses10doigts.mm.core.tool.ToolRegistry} — registre central des outils</li>
 *   <li>{@link fr.ses10doigts.mm.core.tool.ToolExecutionGuard} — couche de securite
 *       transverse (HITL + path validation + timeout)</li>
 *   <li>{@link fr.ses10doigts.mm.core.tool.AgentToolConverter} — adaptateur
 *       {@code AgentTool} vers {@code ToolCallback}</li>
 *   <li>{@link fr.ses10doigts.mm.core.tool.PathValidator} — validation de chemins
 *       contre un workspace</li>
 * </ul>
 */
package fr.ses10doigts.mm.core.tool;
