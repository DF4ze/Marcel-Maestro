package fr.ses10doigts.mm.app.rest.dto;

import fr.ses10doigts.mm.starter.conversation.ConversationEntity;

/**
 * DTO de réponse pour une conversation (E2-M2).
 *
 * @param id        UUID de la conversation (clé d'isolation mémoire)
 * @param projectId ID du projet propriétaire
 * @param title     titre optionnel (null en E2-M2, rempli en E2-M5)
 * @param startedAt timestamp de création ISO-8601
 * @param status    {@code OPEN} ou {@code ARCHIVED}
 */
public record ConversationResponse(
        String id,
        String projectId,
        String title,
        String startedAt,
        String status
) {

    /**
     * Construit le DTO à partir de l'entité JPA.
     *
     * @param entity l'entité conversation
     * @return le DTO correspondant
     */
    public static ConversationResponse from(ConversationEntity entity) {
        return new ConversationResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getTitle(),
                entity.getStartedAt(),
                entity.getStatus().name()
        );
    }
}
