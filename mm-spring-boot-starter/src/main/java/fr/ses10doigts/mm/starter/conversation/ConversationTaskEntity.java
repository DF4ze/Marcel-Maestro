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
 * Entite JPA tracant les taches soumises depuis une conversation.
 */
@Entity
@Table(name = "conversation_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationTaskEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "submitted_at", nullable = false)
    private String submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ConversationTaskStatus status = ConversationTaskStatus.RUNNING;

    /** Agent spécialiste résolu par le qualificateur (ex. {@code claude}, {@code codex}). */
    @Column(name = "agent_id")
    private String agentId;

    /** Catégorie métier déterminée par le qualificateur (ex. {@code CODING}, {@code BUILD}). */
    @Column(name = "category")
    private String category;

    /** Résumé du résultat de la tâche, renseigné à la fermeture de boucle. */
    @Column(name = "result_summary")
    private String resultSummary;

    /** Horodatage ISO-8601 de fin d'exécution, renseigné à la fermeture de boucle. */
    @Column(name = "completed_at")
    private String completedAt;
}
