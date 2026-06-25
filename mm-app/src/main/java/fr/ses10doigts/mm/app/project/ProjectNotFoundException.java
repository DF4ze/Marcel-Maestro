package fr.ses10doigts.mm.app.project;

/**
 * Levée quand un projet demandé par son ID n'existe pas en base.
 */
public class ProjectNotFoundException extends RuntimeException {

    /**
     * @param projectId l'ID du projet introuvable
     */
    public ProjectNotFoundException(String projectId) {
        super("Projet introuvable : " + projectId);
    }
}
