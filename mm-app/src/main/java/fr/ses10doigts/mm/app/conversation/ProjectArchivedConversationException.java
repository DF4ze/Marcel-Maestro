package fr.ses10doigts.mm.app.conversation;

/**
 * Exception levée quand on tente de démarrer une conversation sur un projet archivé.
 *
 * <p>Convertie en {@code 409 Conflict} par ConversationController.</p>
 */
public class ProjectArchivedConversationException extends RuntimeException {

    /**
     * @param projectId l'ID du projet archivé
     */
    public ProjectArchivedConversationException(String projectId) {
        super("Impossible de démarrer une conversation : le projet est archivé (projectId=" + projectId + ")");
    }
}
