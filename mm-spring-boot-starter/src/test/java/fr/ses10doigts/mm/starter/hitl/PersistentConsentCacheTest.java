package fr.ses10doigts.mm.starter.hitl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.starter.memory.MemoryEntryEntity;
import fr.ses10doigts.mm.starter.memory.MemoryEntryRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'intégration du {@link PersistentConsentCache} — E2-M4.
 *
 * <p>Vérifie :</p>
 * <ul>
 *   <li>Validation : {@code ALLOW_PROJECT} sans {@code projectId} → {@link IllegalStateException}</li>
 *   <li>Scope correct en DB : {@code "project:<id>"} pour ALLOW_PROJECT, {@code "global"} pour ALLOW_ALWAYS</li>
 *   <li>Isolation inter-projets au rechargement : les consentements du projet A ne remontent
 *       pas dans le cache du projet B</li>
 *   <li>Rechargement global : ALLOW_ALWAYS rechargé pour tous les projets</li>
 *   <li>Rechargement après redémarrage simulé : isolation par projectId</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("very-slow")
class PersistentConsentCacheTest {

    @Autowired
    private ConsentCache consentCache;

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private MemoryEntryRepository repository;

    @Autowired
    private AgentContextHolder contextHolder;

    @BeforeEach
    void setUp() {
        // Partir sur un holder propre avant chaque test
        contextHolder.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Câblage Spring
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("le bean ConsentCache injecté est bien un PersistentConsentCache")
    void beanEstPersistent() {
        assertThat(consentCache).isInstanceOf(PersistentConsentCache.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ALLOW_ALWAYS — scope global
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ALLOW_ALWAYS est persisté avec scope 'global'")
    void allowAlwaysPersiste_avecScopeGlobal() {
        consentCache.record("build", ConsentDecision.ALLOW_LARGE_ALWAYS);

        Optional<MemoryEntryEntity> entity =
                repository.findByEntryKeyAndTenant("hitl:consent:build", "default");
        assertThat(entity).isPresent();
        assertThat(entity.get().getValue()).isEqualTo("ALLOW_LARGE_ALWAYS");
        assertThat(entity.get().getScope()).isEqualTo("global");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ALLOW_PROJECT — validation et scope
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ALLOW_PROJECT avec projectId valide → scope 'project:<id>' en DB")
    void allowProject_avecProjectIdValide_scopeCorrectEnDB() {
        contextHolder.bind(AgentContext.of("default", "projet-alpha", "conv-1", "task-1"));

        consentCache.record("deploy", ConsentDecision.ALLOW_LARGE_PROJECT);

        Optional<MemoryEntryEntity> entity =
                repository.findByEntryKeyAndTenant("hitl:consent:deploy", "default");
        assertThat(entity).isPresent();
        assertThat(entity.get().getValue()).isEqualTo("ALLOW_LARGE_PROJECT");
        assertThat(entity.get().getScope()).isEqualTo("project:projet-alpha");
    }

    @Test
    @DisplayName("ALLOW_PROJECT sans projectId (contexte non lié) → IllegalStateException")
    void allowProject_sansContexte_leveException() {
        // Aucun bind() sur le holder → projectId null

        assertThatThrownBy(() -> consentCache.record("deploy", ConsentDecision.ALLOW_LARGE_PROJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("*_PROJECT requiert un projectId");

        // Rien ne doit être persisté
        assertThat(repository.findByEntryKeyAndTenant("hitl:consent:deploy", "default")).isEmpty();
    }

    @Test
    @DisplayName("ALLOW_PROJECT avec projectId null explicite → IllegalStateException")
    void allowProject_avecProjectIdNullExplicite_leveException() {
        contextHolder.bind(AgentContext.of("default", null, "conv-1", "task-1"));

        assertThatThrownBy(() -> consentCache.record("deploy", ConsentDecision.ALLOW_LARGE_PROJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("*_PROJECT requiert un projectId");
    }

    @Test
    @DisplayName("ALLOW_PROJECT avec projectId vide → IllegalStateException")
    void allowProject_avecProjectIdVide_leveException() {
        contextHolder.bind(AgentContext.of("default", "  ", "conv-1", "task-1"));

        assertThatThrownBy(() -> consentCache.record("deploy", ConsentDecision.ALLOW_LARGE_PROJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("*_PROJECT requiert un projectId");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Décisions non persistées
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ALLOW_SESSION n'est PAS persisté (mémoire seule)")
    void allowSessionNonPersiste() {
        consentCache.record("read_file", ConsentDecision.ALLOW_LARGE_SESSION);

        assertThat(repository.findByEntryKeyAndTenant("hitl:consent:read_file", "default")).isEmpty();
        // Mais bien présent en cache mémoire
        assertThat(consentCache.lookup("read_file"))
                .isPresent().contains(ConsentDecision.ALLOW_LARGE_SESSION);
    }

    @Test
    @DisplayName("ALLOW_ONCE et DENY ne sont ni cachés ni persistés")
    void onceEtDenyNonPersistes() {
        consentCache.record("risky_tool", ConsentDecision.ALLOW_ONCE);
        consentCache.record("dangerous_tool", ConsentDecision.DENY);

        assertThat(consentCache.lookup("risky_tool")).isEmpty();
        assertThat(consentCache.lookup("dangerous_tool")).isEmpty();
        assertThat(repository.findByEntryKeyAndTenant("hitl:consent:risky_tool", "default")).isEmpty();
        assertThat(repository.findByEntryKeyAndTenant("hitl:consent:dangerous_tool", "default")).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Isolation inter-projets au rechargement
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rechargement projet A : les consentements du projet B ne remontent pas")
    void rechargement_isolationProjetA_ProjetB() {
        // Persistance projet A
        contextHolder.bind(AgentContext.of("default", "projet-a", "conv-a", "task-1"));
        consentCache.record("build", ConsentDecision.ALLOW_LARGE_PROJECT);
        contextHolder.clear();

        // Persistance projet B
        contextHolder.bind(AgentContext.of("default", "projet-b", "conv-b", "task-2"));
        consentCache.record("deploy", ConsentDecision.ALLOW_LARGE_PROJECT);
        contextHolder.clear();

        // Nouveau cache vierge — simule un redémarrage
        PersistentConsentCache freshCache =
                new PersistentConsentCache(memoryStore, repository, contextHolder);

        // Rechargement pour projet A uniquement
        int loaded = freshCache.loadFromStore("projet-a");

        assertThat(loaded).isEqualTo(1);
        assertThat(freshCache.lookup("build"))
                .isPresent().contains(ConsentDecision.ALLOW_LARGE_PROJECT); // projet A ✓
        assertThat(freshCache.lookup("deploy")).isEmpty();              // projet B absent ✓
    }

    @Test
    @DisplayName("rechargement projet B : les consentements du projet A ne remontent pas")
    void rechargement_isolationProjetB_projetAExclu() {
        // Persistance projet A
        contextHolder.bind(AgentContext.of("default", "projet-a", "conv-a", "task-1"));
        consentCache.record("build", ConsentDecision.ALLOW_LARGE_PROJECT);
        contextHolder.clear();

        // Nouveau cache — rechargement pour projet B
        PersistentConsentCache freshCache =
                new PersistentConsentCache(memoryStore, repository, contextHolder);
        int loaded = freshCache.loadFromStore("projet-b");

        assertThat(loaded).isEqualTo(0);
        assertThat(freshCache.lookup("build")).isEmpty(); // projet A exclu ✓
    }

    @Test
    @DisplayName("ALLOW_ALWAYS (scope global) est rechargé pour tous les projets")
    void allowAlways_rechargePourTousProjets() {
        // Persistance ALLOW_ALWAYS (pas de projectId requis)
        consentCache.record("monitor", ConsentDecision.ALLOW_LARGE_ALWAYS);

        // Nouveau cache — rechargement pour projet quelconque
        PersistentConsentCache freshCache =
                new PersistentConsentCache(memoryStore, repository, contextHolder);
        int loaded = freshCache.loadFromStore("n-importe-quel-projet");

        assertThat(loaded).isEqualTo(1);
        assertThat(freshCache.lookup("monitor"))
                .isPresent().contains(ConsentDecision.ALLOW_LARGE_ALWAYS); // global ✓
    }

    @Test
    @DisplayName("rechargement global seul (null projectId) : ALLOW_ALWAYS oui, ALLOW_PROJECT non")
    void rechargementGlobal_excludeAllowProject() {
        // Persistance ALLOW_ALWAYS
        consentCache.record("monitor", ConsentDecision.ALLOW_LARGE_ALWAYS);

        // Persistance ALLOW_PROJECT
        contextHolder.bind(AgentContext.of("default", "projet-x", "conv-x", "task-1"));
        consentCache.record("build", ConsentDecision.ALLOW_LARGE_PROJECT);
        contextHolder.clear();

        // Rechargement global (null = démarrage d'app)
        PersistentConsentCache freshCache =
                new PersistentConsentCache(memoryStore, repository, contextHolder);
        int loaded = freshCache.loadFromStore((String) null);

        assertThat(loaded).isEqualTo(1);
        assertThat(freshCache.lookup("monitor"))
                .isPresent().contains(ConsentDecision.ALLOW_LARGE_ALWAYS);  // global ✓
        assertThat(freshCache.lookup("build")).isEmpty();               // projet exclu ✓
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rechargement après redémarrage simulé
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rechargement après redémarrage : ALLOW_PROJECT de A rechargé uniquement pour A")
    void rechargementApresRedemarrage_isolationProjet() {
        // Écriture initiale — projet A
        contextHolder.bind(AgentContext.of("default", "projet-a", "conv-a", "task-1"));
        consentCache.record("build", ConsentDecision.ALLOW_LARGE_PROJECT);
        contextHolder.clear();

        // Simulation redémarrage : nouveau cache vierge
        PersistentConsentCache freshCache =
                new PersistentConsentCache(memoryStore, repository, contextHolder);

        // Rechargement pour projet A → présent
        freshCache.loadFromStore("projet-a");
        assertThat(freshCache.lookup("build"))
                .isPresent().contains(ConsentDecision.ALLOW_LARGE_PROJECT);

        // Rechargement pour projet B → absent (même cache, add() est idempotent mais filtré)
        PersistentConsentCache cacheB =
                new PersistentConsentCache(memoryStore, repository, contextHolder);
        cacheB.loadFromStore("projet-b");
        assertThat(cacheB.lookup("build")).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Comportement de clearSession
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("clearSession vide le cache mémoire mais les consentements restent en DB")
    void clearSessionConserveLaPersistance() {
        consentCache.record("build", ConsentDecision.ALLOW_LARGE_ALWAYS);
        assertThat(consentCache.lookup("build")).isPresent();

        consentCache.clearSession();
        assertThat(consentCache.lookup("build")).isEmpty(); // cache mémoire vidé

        // DB toujours intacte
        assertThat(repository.findByEntryKeyAndTenant("hitl:consent:build", "default")).isPresent();

        // Et rechargeable
        ((PersistentConsentCache) consentCache).loadFromStore();
        assertThat(consentCache.lookup("build"))
                .isPresent().contains(ConsentDecision.ALLOW_LARGE_ALWAYS);
    }
}
