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
}
