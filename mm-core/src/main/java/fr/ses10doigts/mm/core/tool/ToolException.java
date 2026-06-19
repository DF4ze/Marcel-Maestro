package fr.ses10doigts.mm.core.tool;

/**
 * Échec d'exécution d'un {@link AgentTool}.
 *
 * <p>Exception vérifiée : l'appelant (la boucle, étape 3) doit traiter explicitement
 * l'échec d'outil (typiquement transition vers {@code TROUBLE}).</p>
 */
public class ToolException extends Exception {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
