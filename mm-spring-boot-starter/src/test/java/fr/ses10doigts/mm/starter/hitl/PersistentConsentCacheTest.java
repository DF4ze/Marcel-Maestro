package fr.ses10doigts.mm.starter.hitl;

import static org.assertj.core.api.Assertions.assertThat;

import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.starter.memory.MemoryEntryRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'intégration du {@link PersistentConsentCache} (étape 5, livrable 3).
 *
 * <p>Vérifie que les consentements {@code ALLOW_PROJECT} et {@code ALLOW_ALWAYS} sont
 * persistés dans le {@link MemoryStore} et rechargés au démarrage via
 * {@link PersistentConsentCache#loadFromStore()}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PersistentConsentCacheTest {

    @Autowired
    private ConsentCache consentCache;

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private MemoryEntryRepository repository;

    @Test
    @DisplayName("le bean ConsentCache injecté est bien un PersistentConsentCache")
    void beanEstPersistent() {
        assertThat(consentCache).isInstanceOf(PersistentConsentCache.class);
    }

    @Test
    @DisplayName("ALLOW_ALWAYS est persisté dans le MemoryStore")
    void allowAlwaysPersiste() {
        consentCache.record("build", ConsentDecision.ALLOW_ALWAYS);

        // Vérifier en DB
        var entity = repository.findByEntryKeyAndTenant("hitl:consent:build", "default");
        assertThat(entity).isPresent();
        assertThat(entity.get().getValue()).isEqualTo("ALLOW_ALWAYS");
    }

    @Test
    @DisplayName("ALLOW_PROJECT est persisté dans le MemoryStore")
    void allowProjectPersiste() {
        consentCache.record("deploy", ConsentDecision.ALLOW_PROJECT);

        var entity = repository.findByEntryKeyAndTenant("hitl:consent:deploy", "default");
        assertThat(entity).isPresent();
        assertThat(entity.get().getValue()).isEqualTo("ALLOW_PROJECT");
    }

    @Test
    @DisplayName("ALLOW_SESSION n'est PAS persisté (mémoire seule)")
    void allowSessionNonPersiste() {
        consentCache.record("read_file", ConsentDecision.ALLOW_SESSION);

        var entity = repository.findByEntryKeyAndTenant("hitl:consent:read_file", "default");
        assertThat(entity).isEmpty();

        // Mais bien présent en cache mémoire
        Optional<ConsentDecision> cached = consentCache.lookup("read_file");
        assertThat(cached).isPresent().contains(ConsentDecision.ALLOW_SESSION);
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

    @Test
    @DisplayName("loadFromStore recharge les consentements persistés dans un nouveau cache")
    void rechargementAuDemarrage() {
        // 1. Persister un consentement
        consentCache.record("build", ConsentDecision.ALLOW_ALWAYS);
        consentCache.record("deploy", ConsentDecision.ALLOW_PROJECT);

        // 2. Créer un nouveau cache vierge et recharger depuis le store
        PersistentConsentCache freshCache = new PersistentConsentCache(memoryStore, repository);
        assertThat(freshCache.lookup("build")).isEmpty(); // pas encore chargé

        int loaded = freshCache.loadFromStore();

        // 3. Vérifier que les consentements sont rechargés
        assertThat(loaded).isEqualTo(2);
        assertThat(freshCache.lookup("build")).isPresent().contains(ConsentDecision.ALLOW_ALWAYS);
        assertThat(freshCache.lookup("deploy")).isPresent().contains(ConsentDecision.ALLOW_PROJECT);
    }

    @Test
    @DisplayName("clearSession vide le cache mémoire mais les consentements restent en DB")
    void clearSessionConserveLaPersistance() {
        consentCache.record("build", ConsentDecision.ALLOW_ALWAYS);
        assertThat(consentCache.lookup("build")).isPresent();

        consentCache.clearSession();
        assertThat(consentCache.lookup("build")).isEmpty(); // cache mémoire vidé

        // Mais toujours en DB
        var entity = repository.findByEntryKeyAndTenant("hitl:consent:build", "default");
        assertThat(entity).isPresent();

        // Et rechargeable
        ((PersistentConsentCache) consentCache).loadFromStore();
        assertThat(consentCache.lookup("build")).isPresent().contains(ConsentDecision.ALLOW_ALWAYS);
    }
}
