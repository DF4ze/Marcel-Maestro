package fr.ses10doigts.mm.starter.memory;

import fr.ses10doigts.mm.core.memory.MemoryEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entité JPA représentant une entrée de mémoire factuelle (étape 5, livrable 1).
 *
 * <p>Mappée sur la table {@code memory_entry} créée par Flyway (V1). Sert de
 * couche de persistance pour le port {@link fr.ses10doigts.mm.core.memory.MemoryStore}
 * via {@link JpaMemoryStore}.</p>
 *
 * <p>Convertit vers/depuis le record {@link MemoryEntry} du noyau — le noyau ne
 * voit jamais cette entité JPA.</p>
 */
@Entity
@Table(name = "memory_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_key", nullable = false)
    private String entryKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(nullable = false)
    @Builder.Default
    private String scope = "global";

    @Column(nullable = false)
    @Builder.Default
    private String tenant = "default";

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    /**
     * Convertit le record noyau {@link MemoryEntry} en entité JPA.
     *
     * @param entry record noyau
     * @return entité JPA prête à persister
     */
    public static MemoryEntryEntity fromDomain(MemoryEntry entry) {
        return MemoryEntryEntity.builder()
                .entryKey(entry.key())
                .value(entry.value())
                .scope(entry.scope())
                .tenant(entry.tenant())
                .createdAt(entry.createdAt().toString())
                .updatedAt(entry.updatedAt().toString())
                .build();
    }

    /**
     * Convertit cette entité en record noyau {@link MemoryEntry}.
     *
     * @return record noyau
     */
    public MemoryEntry toDomain() {
        return new MemoryEntry(
                entryKey,
                value,
                scope,
                tenant,
                Instant.parse(createdAt),
                Instant.parse(updatedAt));
    }
}
