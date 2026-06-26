package fr.ses10doigts.mm.starter.hitl;

import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.starter.memory.MemoryEntryEntity;
import fr.ses10doigts.mm.starter.memory.MemoryEntryRepository;
import java.time.Instant;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cache de consentement HITL avec persistance SQLite et isolation par projet (E2-M4).
 *
 * <p>Étend {@link ConsentCache} pour ajouter trois comportements :</p>
 * <ul>
 *   <li><strong>Validation</strong> : {@code ALLOW_PROJECT} requiert un {@code projectId}
 *       non null, non vide, fourni par l'{@link AgentContextHolder} courant. Toute
 *       tentative sans contexte projet lève une {@link IllegalStateException}.</li>
 *   <li><strong>Persistance scopée</strong> : {@code ALLOW_PROJECT} est stocké avec
 *       {@code scope = "project:<projectId>"}. {@code ALLOW_ALWAYS} est stocké avec
 *       {@code scope = "global"}. La clé de scope est construite via
 *       {@link #projectScope(String)} — point unique de construction, jamais dispersé.</li>
 *   <li><strong>Rechargement filtré</strong> : {@link #loadFromStore(String)} ne charge
 *       que les entrées dont le scope correspond au projet courant <em>ou</em> au scope
 *       global. Un consentement du projet A n'est jamais chargé dans le cache d'une
 *       conversation du projet B.</li>
 * </ul>
 *
 * <p>{@code ALLOW_SESSION} reste purement en mémoire (non persisté).
 * {@code ALLOW_ONCE} et {@code DENY} ne sont ni cachés ni persistés.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class PersistentConsentCache extends ConsentCache {

    /** Préfixe des clés de consentement dans le MemoryStore. */
    static final String KEY_PREFIX = "hitl:consent:";

    /** Scope pour les consentements ALLOW_ALWAYS (tous projets). */
    static final String SCOPE_GLOBAL = "global";

    /** Préfixe de scope pour les consentements ALLOW_PROJECT. */
    static final String SCOPE_PROJECT_PREFIX = "project:";

    /** Tenant figé en MVP (ADR-013). */
    private static final String DEFAULT_TENANT = "default";

    @NonNull private final MemoryStore memoryStore;
    @NonNull private final MemoryEntryRepository repository;
    @NonNull private final AgentContextHolder contextHolder;

    // ─────────────────────────────────────────────────────────────────────────
    // Enregistrement
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enregistre un consentement dans le cache mémoire et, si la décision est durable,
     * le persiste dans le MemoryStore avec le scope adéquat.
     *
     * <p>Règle E2-M4 : {@code ALLOW_PROJECT} requiert un {@code projectId} non null et
     * non vide dans le contexte courant ({@link AgentContextHolder}). Si ce n'est pas le
     * cas, une {@link IllegalStateException} est levée avant toute persistance.</p>
     *
     * @param toolName nom de l'outil
     * @param decision décision de l'humain
     * @throws IllegalStateException si {@code ALLOW_PROJECT} est tenté sans projectId
     */
    @Override
    public void record(String toolName, ConsentDecision decision) {
        super.record(toolName, decision);

        if (decision == ConsentDecision.ALLOW_PROJECT) {
            String projectId = contextHolder.projectId();
            log.debug("ALLOW_PROJECT — validation projectId='{}'", projectId);

            if (projectId == null || projectId.isBlank()) {
                throw new IllegalStateException(
                        "ALLOW_PROJECT requiert un projectId — contexte projet non initialisé");
            }

            String scope = projectScope(projectId);
            log.debug("Scope ALLOW_PROJECT — clé construite : '{}'", scope);
            persistConsent(toolName, decision, scope);
            log.info("Consentement ALLOW_PROJECT enregistré — outil='{}', projectId='{}'",
                    toolName, projectId);

        } else if (decision == ConsentDecision.ALLOW_ALWAYS) {
            persistConsent(toolName, decision, SCOPE_GLOBAL);
            log.info("Consentement ALLOW_ALWAYS enregistré — outil='{}', scope=global", toolName);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rechargement
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recharge les consentements persistés depuis le MemoryStore dans le cache mémoire,
     * filtrés par projet.
     *
     * <p>Seules les entrées dont le scope est {@code "global"} (ALLOW_ALWAYS) ou
     * {@code "project:<projectId>"} (ALLOW_PROJECT du projet courant) sont chargées.
     * Un consentement d'un autre projet n'entre jamais dans ce cache.</p>
     *
     * <p>Si {@code projectId} est {@code null}, seules les entrées globales sont chargées
     * (comportement de démarrage d'application).</p>
     *
     * @param projectId identifiant du projet courant ; {@code null} pour les globaux seuls
     * @return nombre de consentements rechargés
     */
    public int loadFromStore(String projectId) {
        List<String> scopes = projectId != null
                ? List.of(SCOPE_GLOBAL, projectScope(projectId))
                : List.of(SCOPE_GLOBAL);

        log.info("Rechargement des consentements HITL persistés — scopes={}", scopes);

        List<MemoryEntryEntity> entities =
                repository.findByEntryKeyStartingWithAndScopeInAndTenant(
                        KEY_PREFIX, scopes, DEFAULT_TENANT);

        int loaded = 0;
        for (var entity : entities) {
            String toolName = entity.getEntryKey().substring(KEY_PREFIX.length());
            try {
                ConsentDecision decision = ConsentDecision.valueOf(entity.getValue());
                super.record(toolName, decision);
                loaded++;
                log.debug("Consentement rechargé : outil='{}', décision={}, scope='{}'",
                        toolName, decision, entity.getScope());
            } catch (IllegalArgumentException e) {
                log.warn("Consentement ignoré (valeur invalide) : clé='{}', valeur='{}'",
                        entity.getEntryKey(), entity.getValue());
            }
        }

        log.info("Rechargement terminé : {} consentement(s) restauré(s)", loaded);
        return loaded;
    }

    /**
     * Recharge uniquement les consentements globaux (ALLOW_ALWAYS).
     * Appelé au démarrage de l'application avant qu'un projectId soit connu.
     *
     * @return nombre de consentements rechargés
     */
    public int loadFromStore() {
        return loadFromStore(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers privés
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construit la clé de scope pour un consentement ALLOW_PROJECT.
     * Point unique de construction — jamais de {@code "project:" + x} dispersé dans le code.
     *
     * @param projectId identifiant du projet
     * @return scope de la forme {@code "project:<projectId>"}
     */
    private static String projectScope(String projectId) {
        return SCOPE_PROJECT_PREFIX + projectId;
    }

    /**
     * Persiste un consentement durable dans le MemoryStore avec le scope fourni.
     *
     * @param toolName nom de l'outil
     * @param decision ALLOW_PROJECT ou ALLOW_ALWAYS
     * @param scope    scope de stockage ({@code "project:<id>"} ou {@code "global"})
     */
    private void persistConsent(String toolName, ConsentDecision decision, String scope) {
        String key = KEY_PREFIX + toolName;
        Instant now = Instant.now();

        MemoryEntry entry = new MemoryEntry(
                key,
                decision.name(),
                scope,
                DEFAULT_TENANT,
                now,
                now);

        memoryStore.put(entry);
        log.debug("Consentement persisté en DB — clé='{}', scope='{}'", key, scope);
    }
}
