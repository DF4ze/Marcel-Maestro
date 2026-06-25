package fr.ses10doigts.mm.app.rest.dto;

import org.springframework.ai.chat.messages.Message;

/**
 * DTO représentant un message de la mémoire chat (E2-M2).
 *
 * @param type    type du message : {@code USER}, {@code ASSISTANT}, {@code SYSTEM} ou {@code TOOL}
 * @param content texte du message
 */
public record MessageResponse(String type, String content) {

    /**
     * Construit le DTO depuis un {@link Message} Spring AI.
     *
     * @param message le message Spring AI
     * @return le DTO correspondant
     */
    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getMessageType().getValue(),
                message.getText()
        );
    }
}
