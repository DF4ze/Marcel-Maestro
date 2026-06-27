package fr.ses10doigts.mm.core.hitl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache de consentement HITL en mémoire (étape 4, livrable 3 ; ADR-005).
 *
 * <p>Stocke les décisions de consentement par clé composite (toolName + scope) pour la
 * durée de la session. Règles de mise en cache :</p>
 * <ul>
 *   <li>{@link ConsentDecision#ALLOW_ONCE} et {@link ConsentDecision#DENY} —
 *       <strong>jamais</strong> cachés.</li>
 *   <li>Toutes les autres décisions (9 combinaisons scope × persistance) — cachées.</li>
 * </ul>
 * <p>La clé de cache est construite par {@link fr.ses10doigts.mm.core.hitl.HitlGuard} :
 * {@code toolName} pour le scope large, {@code toolName::local::<dir>} pour le scope
 * local, {@code toolName::strict::<path>} pour le scope strict.</p>
 *
 * <p>Thread-safe ({@link ConcurrentHashMap}). Pur noyau.</p>
 */
public class ConsentCache {

    private final Map<String, ConsentDecision> cache = new ConcurrentHashMap<>();

    /**
     * Enregistre une décision de consentement. Seules les décisions « session ou plus »
     * sont effectivement stockées.
     *
     * @param toolName nom de l'outil (clé de cache)
     * @param decision décision de l'humain
     */
    public void record(String toolName, ConsentDecision decision) {
        if (toolName == null || decision == null) {
            return;
        }
        switch (decision) {
            case ALLOW_STRICT_SESSION, ALLOW_STRICT_PROJECT, ALLOW_STRICT_ALWAYS,
                 ALLOW_LOCAL_SESSION,  ALLOW_LOCAL_PROJECT,  ALLOW_LOCAL_ALWAYS,
                 ALLOW_LARGE_SESSION,  ALLOW_LARGE_PROJECT,  ALLOW_LARGE_ALWAYS
                    -> cache.put(toolName, decision);
            case ALLOW_ONCE, DENY -> { /* pas de mise en cache */ }
        }
    }

    /**
     * Recherche un consentement déjà accordé pour un outil.
     *
     * @param toolName nom de l'outil
     * @return la décision cachée, ou vide si aucun consentement en cache
     */
    public Optional<ConsentDecision> lookup(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(toolName));
    }

    /**
     * Vide le cache (fin de session).
     */
    public void clearSession() {
        cache.clear();
    }

    /**
     * @return nombre d'entrées en cache (pour tests)
     */
    int size() {
        return cache.size();
    }
}
