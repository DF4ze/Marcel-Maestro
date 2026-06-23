package fr.ses10doigts.mm.core.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import java.util.Map;
import java.util.Optional;

/**
 * Garde-fou HITL : décide <strong>quand</strong> demander un consentement humain avant
 * l'exécution d'un outil (étape 4, livrable 2 ; ADR-005).
 *
 * <p>Le moteur décide quand (ce composant) ; l'hôte décide comment
 * ({@link HumanInteraction}). Flux de {@link #check} :</p>
 * <ol>
 *   <li>La {@link HitlPolicy} tranche : le niveau de risque requiert-il un consentement ?
 *       Si non → {@link HitlVerdict#noConsentNeeded()}.</li>
 *   <li>Le {@link ConsentCache} est consulté : un consentement session (ou supérieur) a-t-il
 *       déjà été accordé pour cet outil ? Si oui → {@link HitlVerdict#allowed(ConsentDecision)}.</li>
 *   <li>Sinon → demande via {@link HumanInteraction#ask(HitlRequest)}.
 *       <ul>
 *         <li>{@code ALLOW_*} → enregistré dans le cache (sauf {@code ONCE}), verdict
 *             autorisé.</li>
 *         <li>{@code DENY} → verdict refusé.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Pur noyau : aucune dépendance infrastructure. L'implémentation concrète du canal
 * humain ({@link HumanInteraction}) est injectée par l'hôte.</p>
 *
 * <p><strong>Note étape 4 → 6</strong> : ce guard est testé et prêt mais n'est pas
 * encore intégré dans la boucle {@code AgentLoop} pour les tool_calls. L'interception se
 * fera à l'étape 6 au même point que {@code AgentTool.execute()} (voir roadmap).</p>
 */
public final class HitlGuard {

    private final HitlPolicy policy;
    private final ConsentCache cache;
    private final HumanInteraction interaction;

    /**
     * @param policy      politique risk → consentement
     * @param cache       cache de consentement session
     * @param interaction canal humain ; ne doit pas être {@code null}
     */
    public HitlGuard(HitlPolicy policy, ConsentCache cache, HumanInteraction interaction) {
        if (policy == null || cache == null || interaction == null) {
            throw new IllegalArgumentException("policy, cache, and interaction must not be null");
        }
        this.policy = policy;
        this.cache = cache;
        this.interaction = interaction;
    }

    /**
     * Évalue si l'exécution d'un outil nécessite un consentement, et le demande si besoin.
     *
     * @param toolName  nom de l'outil à exécuter
     * @param riskLevel niveau de risque déclaré par l'outil
     * @param params    paramètres d'exécution (affichés à l'utilisateur pour contexte)
     * @param ctx       contexte d'exécution courant
     * @return le verdict (autorisé ou refusé)
     */
    public HitlVerdict check(String toolName, RiskLevel riskLevel,
                             Map<String, Object> params, AgentContext ctx) {
        // 1. La politique dit-elle que le consentement est requis ?
        if (!policy.requiresConsent(riskLevel)) {
            return HitlVerdict.noConsentNeeded();
        }

        // 2. Le cache contient-il déjà un consentement pour cet outil ?
        Optional<ConsentDecision> cached = cache.lookup(toolName);
        if (cached.isPresent()) {
            return HitlVerdict.allowed(cached.get());
        }

        // 3. Demander à l'humain
        HitlRequest request = new HitlRequest(
                buildQuestion(toolName, riskLevel, params),
                riskLevel,
                ctx);

        ConsentDecision decision = interaction.ask(request);

        // 4. Enregistrer dans le cache et retourner le verdict
        if (decision == ConsentDecision.DENY) {
            return HitlVerdict.denied("l'utilisateur a refusé l'exécution de '" + toolName + "'");
        }

        cache.record(toolName, decision);
        return HitlVerdict.allowed(decision);
    }

    /**
     * Construit la question affichée à l'utilisateur lors d'une demande HITL.
     *
     * <p>Inclut le nom de l'outil, son niveau de risque et ses paramètres pour que
     * l'utilisateur puisse prendre une décision éclairée (ex : chemin du fichier à écrire).</p>
     *
     * @param toolName  nom de l'outil
     * @param riskLevel niveau de risque
     * @param params    paramètres d'exécution
     * @return question lisible
     */
    private static String buildQuestion(String toolName, RiskLevel riskLevel,
                                        Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("Autoriser l'exécution de '").append(toolName)
          .append("' (risque ").append(riskLevel).append(") ?");

        if (params != null && !params.isEmpty()) {
            sb.append("\n\nParamètres :");
            params.forEach((k, v) -> sb.append("\n  ").append(k).append(" = ").append(v));
        }

        return sb.toString();
    }
}
