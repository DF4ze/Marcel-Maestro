package fr.ses10doigts.mm.app.rest.dto;

import fr.ses10doigts.mm.starter.conversation.ConversationEntity;

/**
 * DTO de reponse pour une conversation.
 *
 * @param id UUID de la conversation
 * @param projectId ID du projet proprietaire
 * @param title titre optionnel
 * @param startedAt timestamp de creation ISO-8601
 * @param status {@code OPEN} ou {@code ARCHIVED}
 * @param messageCount nombre total de messages persistes pour la conversation
 * @param lastMessageAt horodatage ISO-8601 du dernier message, ou null si aucun
 */
public record ConversationResponse(
        String id,
        String projectId,
        String title,
        String startedAt,
        String status,
        long messageCount,
        String lastMessageAt
) {

    /**
     * Construit le DTO a partir de l'entite JPA.
     *
     * @param entity l'entite conversation
     * @return le DTO correspondant
     */
    public static ConversationResponse from(ConversationEntity entity) {
        return new ConversationResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getTitle(),
                entity.getStartedAt(),
                entity.getStatus().name(),
                entity.getMessageCount(),
                entity.getLastMessageAt()
        );
    }
}
