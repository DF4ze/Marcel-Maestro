package fr.ses10doigts.mm.app.project;

/**
 * Exception levee quand une mutation interdite cible un projet systeme protege.
 */
public class ProtectedProjectMutationException extends IllegalStateException {

    public ProtectedProjectMutationException(String message) {
        super(message);
    }
}
