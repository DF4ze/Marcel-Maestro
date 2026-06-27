package fr.ses10doigts.mm.core.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.RiskLevel;

/**
 * Demande de validation humaine soumise via {@link HumanInteraction#ask(HitlRequest)}.
 *
 * <p>Porte la question affichée à l'utilisateur ainsi que les labels de scope qui
 * permettent aux adaptateurs (Telegram, Console) de construire des boutons/options
 * contextuels :</p>
 * <ul>
 *   <li>{@link #strictScopeLabel()} — chemin exact ou commande complète
 *       (ex : {@code "D:\CristalBot\pom.xml"} ou {@code "cat /etc/passwd"}).
 *       {@code null} si l'outil n'a pas de paramètre fichier/commande.</li>
 *   <li>{@link #localScopeLabel()} — répertoire parent ou nom du programme
 *       (ex : {@code "D:\CristalBot"} ou {@code "cat"}).
 *       {@code null} si l'outil n'a pas de paramètre fichier/commande.</li>
 *   <li>{@link #toolName()} — nom technique de l'outil, pour le bouton Large.</li>
 * </ul>
 *
 * @param question         question posée à l'humain (affichée dans le message)
 * @param riskLevel        niveau de risque de l'action concernée
 * @param ctx              contexte d'exécution courant
 * @param toolName         nom de l'outil (bouton Large) ; {@code null} si non applicable
 * @param strictScopeLabel label du scope strict ; {@code null} si non applicable
 * @param localScopeLabel  label du scope local ; {@code null} si non applicable
 */
public record HitlRequest(
        String question,
        RiskLevel riskLevel,
        AgentContext ctx,
        String toolName,
        String strictScopeLabel,
        String localScopeLabel) {

    /**
     * Constructeur simplifié pour les demandes sans scope (ex : clarification AgentLoop).
     *
     * @param question  question posée à l'humain
     * @param riskLevel niveau de risque
     * @param ctx       contexte d'exécution courant
     */
    public HitlRequest(String question, RiskLevel riskLevel, AgentContext ctx) {
        this(question, riskLevel, ctx, null, null, null);
    }
}
