package fr.ses10doigts.mm.core.tool;

/**
 * Résultat d'exécution d'un {@link AgentTool}.
 *
 * @param success {@code true} si l'opération a réussi
 * @param data    données produites (peut être {@code null})
 * @param error   message d'erreur si {@code success == false} (sinon {@code null})
 */
public record ToolResult(boolean success, Object data, String error) {

    /** Résultat de succès portant des données. */
    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null);
    }

    /** Résultat d'échec portant un message d'erreur. */
    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error);
    }
}
