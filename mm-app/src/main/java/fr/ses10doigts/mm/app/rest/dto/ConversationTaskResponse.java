package fr.ses10doigts.mm.app.rest.dto;

import fr.ses10doigts.mm.starter.conversation.ConversationTaskEntity;

/**
 * DTO REST exposant le lien conversation -> tache delegatee.
 *
 * @param id identifiant technique du lien
 * @param conversationId identifiant de la conversation source
 * @param taskId identifiant de la tache soumise
 * @param submittedAt horodatage ISO-8601 de soumission
 * @param status statut courant du lien conversation/tache
 */
public record ConversationTaskResponse(
        String id,
        String conversationId,
        String taskId,
        String submittedAt,
        String status) {

    /**
     * Construit le DTO a partir de l'entite persistante.
     *
     * @param entity entite JPA source
     * @return DTO serialisable
     */
    public static ConversationTaskResponse from(ConversationTaskEntity entity) {
        return new ConversationTaskResponse(
                entity.getId(),
                entity.getConversationId(),
                entity.getTaskId(),
                entity.getSubmittedAt(),
                entity.getStatus().name());
    }
}
