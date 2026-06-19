package fr.ses10doigts.mm.core.engine.parse;

/**
 * Raison d'arrêt d'une génération LLM, normalisée à travers les providers (PB-09).
 *
 * <p>Spring AI expose {@code finishReason} sous forme de {@code String} dont la valeur
 * dépend du provider ({@code "stop"}, {@code "STOP"}, {@code "length"}, …). On la
 * normalise ici, en particulier pour détecter la <strong>troncature</strong>
 * ({@link #LENGTH}) qui doit être traitée comme une difficulté (statut {@code trouble})
 * et non comme un JSON valide.</p>
 */
public enum FinishReason {

    /** Fin normale : le modèle a terminé sa réponse. */
    STOP,
    /** Réponse tronquée : limite de tokens atteinte → JSON probablement incomplet. */
    LENGTH,
    /** Réponse interrompue par un filtre de contenu. */
    CONTENT_FILTER,
    /** Le modèle demande l'exécution d'outils (function calling natif). */
    TOOL_CALLS,
    /** Valeur fournie mais non reconnue. */
    OTHER,
    /** Aucune valeur disponible (provider muet, réponse vide, …). */
    UNKNOWN;

    /**
     * Normalise la valeur brute de {@code finishReason} du provider.
     *
     * @param raw valeur telle que renvoyée par Spring AI (peut être {@code null})
     * @return la constante correspondante, jamais {@code null}
     */
    public static FinishReason fromProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.trim().toLowerCase()) {
            case "stop", "end_turn", "complete", "completed", "finished" -> STOP;
            case "length", "max_tokens", "model_length" -> LENGTH;
            case "content_filter", "safety" -> CONTENT_FILTER;
            case "tool_calls", "tool_use", "function_call" -> TOOL_CALLS;
            default -> OTHER;
        };
    }
}
