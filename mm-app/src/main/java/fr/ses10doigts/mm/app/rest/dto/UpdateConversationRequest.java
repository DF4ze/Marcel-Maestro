package fr.ses10doigts.mm.app.rest.dto;

/**
 * Corps JSON du renommage manuel d'une conversation.
 *
 * @param title le nouveau titre souhaite
 */
public record UpdateConversationRequest(String title) {
}
