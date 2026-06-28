package fr.ses10doigts.mm.app.conversation;

/**
 * Exception levee quand un message est envoye dans une conversation archivee.
 *
 * <p>Une conversation archivee est volontairement en lecture seule : pas de
 * desarchivage et aucun nouveau message accepte.</p>
 */
public class ArchivedConversationReadOnlyException extends RuntimeException {

    /**
     * @param conversationId l'ID de la conversation archivee
     */
    public ArchivedConversationReadOnlyException(String conversationId) {
        super("La conversation est archivee et n'accepte plus de nouveaux messages (conversationId="
                + conversationId + ")");
    }
}
