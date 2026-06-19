package fr.ses10doigts.mm.core.hitl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache de consentement HITL en mémoire (étape 4, livrable 3 ; ADR-005).
 *
 * <p>Stocke les décisions de consentement par nom d'outil pour la durée de la session.
 * Règles de mise en cache :</p>
 * <ul>
 *   <li>{@link ConsentDecision#ALLOW_ONCE} — <strong>jamais</strong> caché (redemandé à
 *       chaque appel)</li>
 *   <li>{@link ConsentDecision#ALLOW_SESSION} — caché pour la session courante</li>
 *   <li>{@link ConsentDecision#ALLOW_PROJECT} — caché <strong>comme session</strong>
 *       (couture pour la persistance étape 5, {@code FactStore})</li>
 *   <li>{@link ConsentDecision#ALLOW_ALWAYS} — caché <strong>comme session</strong>
 *       (idem, persistance étape 5)</li>
 *   <li>{@link ConsentDecision#DENY} — <strong>jamais</strong> caché (l'humain peut
 *       changer d'avis au prochain appel)</li>
 * </ul>
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
            case ALLOW_SESSION, ALLOW_PROJECT, ALLOW_ALWAYS -> cache.put(toolName, decision);
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
