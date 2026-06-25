package fr.ses10doigts.mm.starter.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entité JPA représentant une conversation Marcel Maestro (E2-M2).
 *
 * <p>Mappée sur la table {@code conversation} créée par la migration Flyway V3.
 * L'ID ({@code UUID v4}) est également la clé d'isolation mémoire passée à
 * {@code ChatMemory.add(conversationId, message)} — aucun calcul supplémentaire
 * n'est nécessaire pour router les messages Spring AI vers la bonne partition.</p>
 *
 * <p>Le champ {@code title} est nullable : il sera rempli par le LLM en E2-M5
 * (génération automatique de titre). En E2-M2, les conversations n'ont pas de titre.</p>
 *
 * <p>La relation {@code project_id → project.id} est imposée par le schéma SQL
 * (REFERENCES + ON DELETE CASCADE). La contrainte FK n'est pas répercutée au niveau
 * JPA {@code @ManyToOne} pour éviter des eager loads non désirés ; le service
 * vérifie l'existence du projet avant insertion.</p>
 *
 * <p>Cette entité ne doit jamais remonter dans {@code mm-core} : le noyau ne
 * connaît que des records domaine ou des ports (ADR-002, ADR-003).</p>
 */
@Entity
@Table(name = "conversation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationEntity {

    /**
     * UUID v4 généré par {@code ConversationService} avant insertion.
     * Sert également de clé d'isolation mémoire ({@code conversationId} Spring AI).
     */
    @Id
    @Column(nullable = false)
    private String id;

    /**
     * ID du projet propriétaire. Clé étrangère vers {@code project.id}.
     * La contrainte ON DELETE CASCADE est portée par le schéma Flyway V3.
     */
    @Column(name = "project_id", nullable = false)
    private String projectId;

    /**
     * Titre optionnel de la conversation (couture pour E2-M5 — LLM title).
     * Nullable en E2-M2, aucune logique associée.
     */
    @Column
    private String title;

    /** Timestamp de création, ISO-8601 (TEXT dans SQLite, compatible JPA). */
    @Column(name = "started_at", nullable = false)
    private String startedAt;

    /** Statut du cycle de vie (OPEN | ARCHIVED). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ConversationStatus status = ConversationStatus.OPEN;
}
