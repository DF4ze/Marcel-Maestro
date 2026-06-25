package fr.ses10doigts.mm.app.conversation;

/**
 * Exception levée quand une conversation est demandée par ID mais n'existe pas en DB.
 *
 * <p>Convertie en {@code 404 Not Found} par ConversationController.</p>
 */
public class ConversationNotFoundException extends RuntimeException {

    /**
     * @param id l'ID de la conversation introuvable
     */
    public ConversationNotFoundException(String id) {
        super("Conversation introuvable : " + id);
    }
}
