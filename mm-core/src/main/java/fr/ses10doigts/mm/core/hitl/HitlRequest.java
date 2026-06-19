package fr.ses10doigts.mm.core.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.RiskLevel;

/**
 * Demande de validation humaine soumise via {@link HumanInteraction#ask(HitlRequest)}.
 *
 * @param question  question posée à l'humain
 * @param riskLevel niveau de risque de l'action concernée
 * @param ctx       contexte d'exécution courant
 */
public record HitlRequest(String question, RiskLevel riskLevel, AgentContext ctx) {
}
