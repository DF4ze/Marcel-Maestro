package fr.ses10doigts.mm.starter.hitl;

import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.starter.memory.MemoryEntryRepository;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Cache de consentement HITL avec persistance SQLite (étape 5, livrable 3).
 *
 * <p>Étend {@link ConsentCache} pour ajouter deux comportements :</p>
 * <ul>
 *   <li><strong>Persistance</strong> : les décisions {@code ALLOW_PROJECT} et
 *       {@code ALLOW_ALWAYS} sont écrites dans le {@link MemoryStore} en plus du cache
 *       mémoire. Convention de clé : {@code hitl:consent:<toolName>}.</li>
 *   <li><strong>Rechargement</strong> : au démarrage ({@link #loadFromStore}), les
 *       consentements persistés sont relus et injectés dans le cache mémoire, avant
 *       le premier appel LLM.</li>
 * </ul>
 *
 * <p>Les consentements {@code ALLOW_SESSION} restent purement en mémoire (non persistés).
 * Le scope est {@code "global"} pour {@code ALLOW_ALWAYS}, non persisté pour
 * {@code ALLOW_PROJECT} dans cette première itération (tenant figé, projet unique).</p>
 */
@Slf4j
public class PersistentConsentCache extends ConsentCache {

    /** Préfixe des clés de consentement dans le MemoryStore. */
    static final String KEY_PREFIX = "hitl:consent:";

    /** Tenant figé en MVP (ADR-013). */
    private static final String DEFAULT_TENANT = "default";

    /** Scope pour les consentements ALLOW_ALWAYS. */
    private static final String SCOPE_GLOBAL = "global";

    private final MemoryStore memoryStore;
    private final MemoryEntryRepository repository;

    /**
     * @param memoryStore store de mémoire factuelle (port noyau)
     * @param repository  repository JPA pour les requêtes par préfixe de clé
     */
    public PersistentConsentCache(MemoryStore memoryStore, MemoryEntryRepository repository) {
        super();
        this.memoryStore = memoryStore;
        this.repository = repository;
    }

    /**
     * Enregistre un consentement dans le cache mémoire et, si la décision est durable
     * ({@code ALLOW_PROJECT} ou {@code ALLOW_ALWAYS}), le persiste dans le MemoryStore.
     *
     * @param toolName nom de l'outil
     * @param decision décision de l'humain
     */
    @Override
    public void record(String toolName, ConsentDecision decision) {
        super.record(toolName, decision);

        if (decision == ConsentDecision.ALLOW_PROJECT
                || decision == ConsentDecision.ALLOW_ALWAYS) {
            persistConsent(toolName, decision);
        }
    }

    /**
     * Recharge les consentements persistés depuis le MemoryStore dans le cache mémoire.
     * Doit être appelé au démarrage de l'application, avant le premier appel LLM.
     *
     * @return nombre de consentements rechargés
     */
    public int loadFromStore() {
        log.info("Rechargement des consentements HITL persistés…");

        List<fr.ses10doigts.mm.starter.memory.MemoryEntryEntity> entities =
                repository.findByEntryKeyStartingWithAndTenant(KEY_PREFIX, DEFAULT_TENANT);

        int loaded = 0;
        for (var entity : entities) {
            String toolName = entity.getEntryKey().substring(KEY_PREFIX.length());
            try {
                ConsentDecision decision = ConsentDecision.valueOf(entity.getValue());
                super.record(toolName, decision);
                loaded++;
                log.debug("Consentement rechargé : outil='{}', décision={}", toolName, decision);
            } catch (IllegalArgumentException e) {
                log.warn("Consentement ignoré (valeur invalide) : clé='{}', valeur='{}'",
                        entity.getEntryKey(), entity.getValue());
            }
        }

        log.info("Rechargement terminé : {} consentement(s) restauré(s)", loaded);
        return loaded;
    }

    /**
     * Persiste un consentement durable dans le MemoryStore.
     *
     * @param toolName nom de l'outil
     * @param decision ALLOW_PROJECT ou ALLOW_ALWAYS
     */
    private void persistConsent(String toolName, ConsentDecision decision) {
        String key = KEY_PREFIX + toolName;
        Instant now = Instant.now();

        MemoryEntry entry = new MemoryEntry(
                key,
                decision.name(),
                SCOPE_GLOBAL,
                DEFAULT_TENANT,
                now,
                now);

        memoryStore.put(entry);
        log.info("Consentement persisté : outil='{}', décision={}", toolName, decision);
    }
}
