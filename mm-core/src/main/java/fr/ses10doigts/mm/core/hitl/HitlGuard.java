package fr.ses10doigts.mm.core.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import java.nio.file.Path;
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
 *   <li>Le {@link ConsentCache} est consulté en <strong>3 passes</strong> :
 *     <ol>
 *       <li>Large (clé = {@code toolName}) — scope le plus permissif</li>
 *       <li>Local (clé = {@code toolName::local::<dir_ou_programme>})</li>
 *       <li>Strict (clé = {@code toolName::strict::<chemin_ou_commande>})</li>
 *     </ol>
 *     Si un hit est trouvé → {@link HitlVerdict#allowed(ConsentDecision)}.
 *   </li>
 *   <li>Sinon → demande via {@link HumanInteraction#ask(HitlRequest)}.
 *       La décision est enregistrée dans le cache avec la clé correspondant au scope
 *       choisi par l'utilisateur.</li>
 * </ol>
 *
 * <p>Pur noyau : aucune dépendance infrastructure.</p>
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

        // Extraction des scopes depuis les paramètres
        String strictScope = extractStrictScope(params);
        String localScope  = extractLocalScope(params);

        // 2. Lookup en 3 passes (large → local → strict)
        Optional<ConsentDecision> cached = lookupScoped(toolName, strictScope, localScope);
        if (cached.isPresent()) {
            log.debug("Cache hit — outil='{}', décision={}", toolName, cached.get());
            return HitlVerdict.allowed(cached.get());
        }

        // 3. Demander à l'humain
        HitlRequest request = new HitlRequest(
                buildQuestion(toolName, toolDescription, riskLevel, params),
                riskLevel,
                ctx,
                toolName,
                strictScope,
                localScope);

        log.info("HITL ask — outil='{}', riskLevel={}, question : {}", toolName, riskLevel,
                request.question().lines().limit(3).reduce("", (a, b) -> a + "\n  " + b).strip());

        ConsentDecision decision = interaction.ask(request);

        // 4. Enregistrer dans le cache avec la clé de scope appropriée
        if (decision == ConsentDecision.DENY) {
            return HitlVerdict.denied("l'utilisateur a refusé l'exécution de '" + toolName + "'");
        }

        String cacheKey = buildCacheKey(toolName, decision, strictScope, localScope);
        cache.record(cacheKey, decision);
        log.info("HITL accordé — outil='{}', décision={}, clé='{}'", toolName, decision, cacheKey);
        return HitlVerdict.allowed(decision);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup scopé
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Consulte le cache en 3 passes : large (outil entier), local (dir/programme),
     * strict (chemin/commande exact).
     *
     * <p>Un hit sur le scope Large couvre tous les chemins de cet outil (le plus permissif).
     * Un hit Local couvre les appels dans le même répertoire ou avec le même programme.
     * Un hit Strict ne couvre que la même cible exacte.</p>
     */
    private Optional<ConsentDecision> lookupScoped(String toolName,
                                                    String strictScope, String localScope) {
        // 2a. Large (clé = toolName seul)
        Optional<ConsentDecision> large = cache.lookup(toolName);
        if (large.isPresent()) return large;

        // 2b. Local (clé = toolName::local::<dir_ou_programme>)
        if (localScope != null) {
            Optional<ConsentDecision> local = cache.lookup(localKey(toolName, localScope));
            if (local.isPresent()) return local;
        }

        // 2c. Strict (clé = toolName::strict::<chemin_ou_commande>)
        if (strictScope != null) {
            Optional<ConsentDecision> strict = cache.lookup(strictKey(toolName, strictScope));
            if (strict.isPresent()) return strict;
        }

        return Optional.empty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construction de la clé de cache
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construit la clé de cache selon le scope choisi dans la décision.
     * Fallback sur {@code toolName} si le scope correspondant n'est pas disponible.
     */
    private static String buildCacheKey(String toolName, ConsentDecision decision,
                                         String strictScope, String localScope) {
        return switch (decision) {
            case ALLOW_STRICT_SESSION, ALLOW_STRICT_PROJECT, ALLOW_STRICT_ALWAYS ->
                    strictKey(toolName, strictScope);
            case ALLOW_LOCAL_SESSION, ALLOW_LOCAL_PROJECT, ALLOW_LOCAL_ALWAYS ->
                    localKey(toolName, localScope);
            default -> toolName; // ALLOW_LARGE_* → clé = toolName (scope outil entier)
        };
    }

    private static String strictKey(String toolName, String strictScope) {
        return strictScope != null ? toolName + "::strict::" + strictScope : toolName;
    }

    private static String localKey(String toolName, String localScope) {
        return localScope != null ? toolName + "::local::" + localScope : toolName;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extraction de scope
    // ─────────────────────────────────────────────────────────────────────────

    /** Longueur max d'une valeur de paramètre avant troncature (pour Telegram notamment). */
    private static final int MAX_PARAM_VALUE_LENGTH = 120;

    /**
     * Clés de paramètres identifiées comme des chemins fichier.
     */
    private static final Set<String> PATH_PARAM_KEYS = Set.of("path", "file", "filepath",
            "filename", "destination", "source", "target", "dir", "directory");

    /**
     * Clés de paramètres identifiées comme des commandes shell.
     */
    private static final Set<String> COMMAND_PARAM_KEYS = Set.of(
            "command", "cmd", "args", "script", "executable", "exec");

    /**
     * Scope strict : chemin exact (pour les outils fichier) ou commande complète
     * (pour les outils shell). {@code null} si aucun paramètre approprié n'est trouvé.
     */
    private static String extractStrictScope(Map<String, Object> params) {
        if (params == null) return null;
        for (String key : PATH_PARAM_KEYS) {
            Object val = params.get(key);
            if (val != null) return String.valueOf(val);
        }
        for (String key : COMMAND_PARAM_KEYS) {
            Object val = params.get(key);
            if (val != null) return String.valueOf(val);
        }
        return null;
    }

    /**
     * Scope local : répertoire parent du fichier, ou premier mot (programme) de la commande.
     * {@code null} si aucun paramètre approprié n'est trouvé.
     */
    private static String extractLocalScope(Map<String, Object> params) {
        if (params == null) return null;
        for (String key : PATH_PARAM_KEYS) {
            Object val = params.get(key);
            if (val != null) {
                try {
                    Path p = Path.of(String.valueOf(val));
                    Path parent = p.getParent();
                    return parent != null ? parent.toString() : null;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        for (String key : COMMAND_PARAM_KEYS) {
            Object val = params.get(key);
            if (val != null) {
                String cmd = String.valueOf(val).strip();
                int space = cmd.indexOf(' ');
                return space > 0 ? cmd.substring(0, space) : cmd;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construction du message HITL
    // ─────────────────────────────────────────────────────────────────────────

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
                if (PATH_PARAM_KEYS.contains(k.toLowerCase())
                        || COMMAND_PARAM_KEYS.contains(k.toLowerCase())) {
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
            for (String p : paths) {
                sb.append("\n→ ").append(p);
            }
        } else if (toolDescription != null && !toolDescription.isBlank()) {
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
