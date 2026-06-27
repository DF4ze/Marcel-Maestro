package fr.ses10doigts.mm.starter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import java.time.Instant;
import java.util.List;
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
 * Tests d'intégration du {@link JpaMemoryStore} sur SQLite in-memory (étape 5).
 *
 * <p>Vérifie le cycle complet : put → get → findByScope → delete, ainsi que
 * le comportement upsert (mise à jour si la clé existe déjà).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
class JpaMemoryStoreTest {

    @Autowired
    private MemoryStore memoryStore;

    private final AgentContext ctx = AgentContext.of("default", "test-project", "conv-1", "task-1");

    private MemoryEntry sampleEntry;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        sampleEntry = new MemoryEntry("test:key", "test-value", "global", "default", now, now);
    }

    @Test
    @DisplayName("put → get retourne l'entrée persistée")
    void putThenGet() {
        memoryStore.put(sampleEntry);

        Optional<MemoryEntry> result = memoryStore.get("test:key", ctx);

        assertThat(result).isPresent();
        assertThat(result.get().key()).isEqualTo("test:key");
        assertThat(result.get().value()).isEqualTo("test-value");
        assertThat(result.get().scope()).isEqualTo("global");
        assertThat(result.get().tenant()).isEqualTo("default");
    }

    @Test
    @DisplayName("get sur clé inexistante retourne vide")
    void getInexistant() {
        Optional<MemoryEntry> result = memoryStore.get("inexistant", ctx);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("put met à jour la valeur si la clé existe déjà (upsert)")
    void putUpsert() {
        memoryStore.put(sampleEntry);

        Instant later = Instant.now();
        MemoryEntry updated = new MemoryEntry("test:key", "new-value", "project:abc", "default", later, later);
        memoryStore.put(updated);

        Optional<MemoryEntry> result = memoryStore.get("test:key", ctx);
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("new-value");
        assertThat(result.get().scope()).isEqualTo("project:abc");
    }

    @Test
    @DisplayName("findByScope retourne toutes les entrées du scope")
    void findByScope() {
        Instant now = Instant.now();
        memoryStore.put(new MemoryEntry("key1", "val1", "global", "default", now, now));
        memoryStore.put(new MemoryEntry("key2", "val2", "global", "default", now, now));
        memoryStore.put(new MemoryEntry("key3", "val3", "project:abc", "default", now, now));

        List<MemoryEntry> globalEntries = memoryStore.findByScope("global", ctx);
        assertThat(globalEntries).hasSize(2);
        assertThat(globalEntries).extracting(MemoryEntry::key).containsExactlyInAnyOrder("key1", "key2");

        List<MemoryEntry> projectEntries = memoryStore.findByScope("project:abc", ctx);
        assertThat(projectEntries).hasSize(1);
        assertThat(projectEntries.getFirst().key()).isEqualTo("key3");
    }

    @Test
    @DisplayName("delete supprime l'entrée")
    void delete() {
        memoryStore.put(sampleEntry);
        assertThat(memoryStore.get("test:key", ctx)).isPresent();

        memoryStore.delete("test:key", ctx);
        assertThat(memoryStore.get("test:key", ctx)).isEmpty();
    }

    @Test
    @DisplayName("delete sur clé inexistante ne lève pas d'exception")
    void deleteInexistant() {
        memoryStore.delete("inexistant", ctx);
        // pas d'exception = succès
    }

    @Test
    @DisplayName("isolation par tenant : un tenant ne voit pas les données de l'autre")
    void isolationTenant() {
        Instant now = Instant.now();
        memoryStore.put(new MemoryEntry("shared:key", "tenant-a", "global", "default", now, now));

        AgentContext otherCtx = AgentContext.of("other-tenant", "test-project", "conv-1", "task-1");
        Optional<MemoryEntry> result = memoryStore.get("shared:key", otherCtx);
        assertThat(result).isEmpty();
    }
}
