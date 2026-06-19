package fr.ses10doigts.mm.starter.memory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository Spring Data JPA pour les entrées de mémoire factuelle (étape 5).
 *
 * <p>Requêtes dérivées filtrées par {@code tenant} — le champ est présent dès J1
 * (ADR-013) mais figé à {@code "default"} en MVP.</p>
 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntryEntity, Long> {

    /**
     * Recherche une entrée par clé et tenant.
     *
     * @param entryKey clé de l'entrée
     * @param tenant   identifiant artisan
     * @return l'entrée si elle existe
     */
    Optional<MemoryEntryEntity> findByEntryKeyAndTenant(String entryKey, String tenant);

    /**
     * Recherche toutes les entrées d'un scope pour un tenant donné.
     *
     * @param scope  portée ({@code "global"}, {@code "project:<id>"}, …)
     * @param tenant identifiant artisan
     * @return liste des entrées correspondantes
     */
    List<MemoryEntryEntity> findByScopeAndTenant(String scope, String tenant);

    /**
     * Supprime une entrée par clé et tenant.
     *
     * @param entryKey clé de l'entrée
     * @param tenant   identifiant artisan
     */
    void deleteByEntryKeyAndTenant(String entryKey, String tenant);

    /**
     * Recherche toutes les entrées dont la clé commence par un préfixe, pour un tenant.
     *
     * @param prefix préfixe de clé (ex. {@code "hitl:consent:"})
     * @param tenant identifiant artisan
     * @return liste des entrées correspondantes
     */
    List<MemoryEntryEntity> findByEntryKeyStartingWithAndTenant(String prefix, String tenant);
}
