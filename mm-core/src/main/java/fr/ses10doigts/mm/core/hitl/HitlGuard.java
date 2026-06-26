package fr.ses10doigts.mm.core.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
     * @param toolName        nom de l'outil à exécuter
     * @param toolDescription description lisible de l'outil (affichée à l'utilisateur)
     * @param riskLevel       niveau de risque déclaré par l'outil
     * @param params          paramètres d'exécution (affichés à l'utilisateur pour contexte)
     * @param ctx             contexte d'exécution courant
     * @return le verdict (autorisé ou refusé)
     */
    public HitlVerdict check(String toolName, String toolDescription, RiskLevel riskLevel,
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
                buildQuestion(toolName, toolDescription, riskLevel, params),
                riskLevel,
                ctx);

        log.info("HITL ask — outil='{}', riskLevel={}, question : {}", toolName, riskLevel,
                request.question().lines().limit(3).reduce("", (a, b) -> a + "\n  " + b).strip());

        ConsentDecision decision = interaction.ask(request);

        // 4. Enregistrer dans le cache et retourner le verdict
        if (decision == ConsentDecision.DENY) {
            return HitlVerdict.denied("l'utilisateur a refusé l'exécution de '" + toolName + "'");
        }

        cache.record(toolName, decision);
        return HitlVerdict.allowed(decision);
    }

    /** Longueur max d'une valeur de paramètre avant troncature (pour Telegram notamment). */
    private static final int MAX_PARAM_VALUE_LENGTH = 120;

    /**
     * Clés de paramètres identifiées comme des chemins fichier — affichées en premier,
     * mises en évidence dans le message HITL.
     */
    private static final Set<String> PATH_PARAM_KEYS = Set.of("path", "file", "filepath",
            "filename", "destination", "source", "target", "dir", "directory");

    /**
     * Construit la question affichée à l'utilisateur lors d'une demande HITL.
     *
     * <p>Format (priorité à la lisibilité immédiate) :</p>
     * <ol>
     *   <li>En-tête : nom de l'outil</li>
     *   <li>Chemin(s) impacté(s) <strong>en premier</strong> — l'utilisateur doit voir
     *       immédiatement quel fichier est concerné, sans avoir à lire tout le message</li>
     *   <li>Autres paramètres, tronqués à {@value #MAX_PARAM_VALUE_LENGTH} caractères
     *       pour ne pas noyer l'information clé</li>
     *   <li>Si aucun chemin, fallback sur la description fonctionnelle de l'outil</li>
     * </ol>
     *
     * @param toolName        nom technique de l'outil
     * @param toolDescription description lisible de l'outil (fallback si pas de chemin)
     * @param riskLevel       niveau de risque (non affiché ici — déjà dans le titre Telegram/Console)
     * @param params          paramètres d'exécution
     * @return question lisible pour l'humain
     */
    private static String buildQuestion(String toolName, String toolDescription,
                                        RiskLevel riskLevel, Map<String, Object> params) {
        List<String> paths = new ArrayList<>();
        List<String> others = new ArrayList<>();

        if (params != null) {
            params.forEach((k, v) -> {
                if (v == null) return;
                String s = String.valueOf(v);
                if (PATH_PARAM_KEYS.contains(k.toLowerCase())) {
                    paths.add(s);
                } else {
                    if (s.length() > MAX_PARAM_VALUE_LENGTH) {
                        s = s.substring(0, MAX_PARAM_VALUE_LENGTH) + "… [tronqué]";
                    }
                    others.add(k + " = " + s);
                }
            });
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Autorisation requise : ").append(toolName);

        if (!paths.isEmpty()) {
            // Chemin(s) en évidence immédiate — raison principale du HITL
            for (String p : paths) {
                sb.append("\n→ ").append(p);
            }
        } else if (toolDescription != null && !toolDescription.isBlank()) {
            // Pas de chemin : afficher la description comme contexte
            sb.append("\n").append(toolDescription);
        }

        if (!others.isEmpty()) {
            sb.append("\n");
            for (String o : others) {
                sb.append("\n• ").append(o);
            }
        }

        return sb.toString();
    }
}
