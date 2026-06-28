package fr.ses10doigts.mm.app.rest.dto;

/**
 * DTO REST exposant le brief courant d'une conversation.
 *
 * @param conversationId identifiant de la conversation resumee
 * @param brief texte du brief produit
 */
public record ConversationBriefResponse(String conversationId, String brief) {
}
