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
 * Entite JPA representant une conversation Marcel Maestro (E2-M2).
 *
 * <p>Mappee sur la table {@code conversation} creee par les migrations Flyway.
 * L'ID ({@code UUID v4}) est egalement la cle d'isolation memoire passee a
 * {@code ChatMemory.add(conversationId, message)} - aucun calcul supplementaire
 * n'est necessaire pour router les messages Spring AI vers la bonne partition.</p>
 *
 * <p>Le champ {@code title} est nullable : il est rempli par le LLM ou par
 * renommage manuel. La relation {@code project_id -> project.id} est imposee
 * par le schema SQL (REFERENCES + ON DELETE CASCADE) sans {@code @ManyToOne}
 * pour eviter des eager loads inutiles.</p>
 *
 * <p>E3-M5 choisit une colonne persistee pour {@code messageCount} et
 * {@code lastMessageAt} via Flyway V4 plutot qu'une requete native sur
 * {@code SPRING_AI_CHAT_MEMORY} : la lecture REST reste ainsi decouplee du
 * schema interne Spring AI.</p>
 *
 * <p>Cette entite ne doit jamais remonter dans {@code mm-core} : le noyau ne
 * connait que des records domaine ou des ports (ADR-002, ADR-003).</p>
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
     * UUID v4 genere par {@code ConversationService} avant insertion.
     * Sert egalement de cle d'isolation memoire ({@code conversationId} Spring AI).
     */
    @Id
    @Column(nullable = false)
    private String id;

    /**
     * ID du projet proprietaire. Cle etrangere vers {@code project.id}.
     */
    @Column(name = "project_id", nullable = false)
    private String projectId;

    /**
     * Titre optionnel de la conversation.
     */
    @Column
    private String title;

    /** Timestamp de creation, ISO-8601 (TEXT dans SQLite, compatible JPA). */
    @Column(name = "started_at", nullable = false)
    private String startedAt;

    /** Statut du cycle de vie (OPEN | ARCHIVED). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ConversationStatus status = ConversationStatus.OPEN;

    /** Nombre total de messages persistés pour cette conversation. */
    @Column(name = "message_count", nullable = false)
    @Builder.Default
    private int messageCount = 0;

    /** Horodatage ISO-8601 du dernier message persiste, null si aucun message. */
    @Column(name = "last_message_at")
    private String lastMessageAt;
}
