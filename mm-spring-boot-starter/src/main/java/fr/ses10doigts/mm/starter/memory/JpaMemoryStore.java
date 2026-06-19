package fr.ses10doigts.mm.starter.memory;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implémentation JPA/SQLite du port {@link MemoryStore} (étape 5, livrable 1).
 *
 * <p>Vit dans {@code mm-spring-boot-starter} — jamais dans {@code mm-core}. Convertit
 * entre le record noyau {@link MemoryEntry} et l'entité JPA {@link MemoryEntryEntity}.
 * Filtre systématiquement par {@code tenant} (figé à {@code "default"} en MVP,
 * ADR-013).</p>
 *
 * <p>Upsert sémantique : {@link #put} crée ou met à jour selon l'existence de la clé.</p>
 */
@Slf4j
@RequiredArgsConstructor
@Transactional
public class JpaMemoryStore implements MemoryStore {

    private final MemoryEntryRepository repository;

    /**
     * Persiste ou met à jour une entrée mémoire. Si la clé existe déjà pour le même
     * tenant, la valeur, le scope et le timestamp {@code updatedAt} sont mis à jour.
     *
     * @param entry entrée à persister
     */
    @Override
    public void put(MemoryEntry entry) {
        log.info("Persistance mémoire : clé='{}', scope='{}', tenant='{}'",
                entry.key(), entry.scope(), entry.tenant());

        Optional<MemoryEntryEntity> existing =
                repository.findByEntryKeyAndTenant(entry.key(), entry.tenant());

        if (existing.isPresent()) {
            MemoryEntryEntity entity = existing.get();
            entity.setValue(entry.value());
            entity.setScope(entry.scope());
            entity.setUpdatedAt(Instant.now().toString());
            repository.save(entity);
            log.debug("Entrée mise à jour : clé='{}', id={}", entry.key(), entity.getId());
        } else {
            MemoryEntryEntity entity = MemoryEntryEntity.fromDomain(entry);
            repository.save(entity);
            log.debug("Nouvelle entrée créée : clé='{}', id={}", entry.key(), entity.getId());
        }
    }

    /**
     * Recherche une entrée par clé, filtrée par le tenant du contexte.
     *
     * @param key clé de l'entrée
     * @param ctx contexte d'exécution (fournit le tenant)
     * @return l'entrée si elle existe
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<MemoryEntry> get(String key, AgentContext ctx) {
        log.debug("Lecture mémoire : clé='{}', tenant='{}'", key, ctx.tenant());
        return repository.findByEntryKeyAndTenant(key, ctx.tenant())
                .map(MemoryEntryEntity::toDomain);
    }

    /**
     * Recherche toutes les entrées d'un scope, filtrées par le tenant du contexte.
     *
     * @param scope portée ({@code "global"}, {@code "project:<id>"}, …)
     * @param ctx   contexte d'exécution (fournit le tenant)
     * @return liste des entrées correspondantes
     */
    @Override
    @Transactional(readOnly = true)
    public List<MemoryEntry> findByScope(String scope, AgentContext ctx) {
        log.debug("Recherche par scope='{}', tenant='{}'", scope, ctx.tenant());
        List<MemoryEntry> results = repository.findByScopeAndTenant(scope, ctx.tenant())
                .stream()
                .map(MemoryEntryEntity::toDomain)
                .toList();
        log.info("findByScope('{}') → {} entrée(s) trouvée(s)", scope, results.size());
        return results;
    }

    /**
     * Supprime une entrée par clé, filtrée par le tenant du contexte.
     *
     * @param key clé de l'entrée à supprimer
     * @param ctx contexte d'exécution (fournit le tenant)
     */
    @Override
    public void delete(String key, AgentContext ctx) {
        log.info("Suppression mémoire : clé='{}', tenant='{}'", key, ctx.tenant());
        repository.deleteByEntryKeyAndTenant(key, ctx.tenant());
    }
}
