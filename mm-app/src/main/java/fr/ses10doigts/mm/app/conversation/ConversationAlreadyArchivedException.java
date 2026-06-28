package fr.ses10doigts.mm.app.conversation;

/**
 * Exception levee quand une conversation deja archivee est archivee a nouveau.
 *
 * <p>Convertie en {@code 409 Conflict} par ConversationController.</p>
 */
public class ConversationAlreadyArchivedException extends RuntimeException {

    /**
     * @param conversationId l'ID de la conversation deja archivee
     */
    public ConversationAlreadyArchivedException(String conversationId) {
        super("La conversation est deja archivee (conversationId=" + conversationId + ")");
    }
}
