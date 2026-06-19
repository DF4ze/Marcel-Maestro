package fr.ses10doigts.mm.core.engine;

/**
 * Paramètres de bornage de la boucle agentique (étape 3, livrable 5 ; PB-07 cas 4).
 *
 * <p>Tous configurables par l'hôte. La borne {@code maxIterations} est nécessaire mais
 * insuffisante : un LLM peut alterner indéfiniment entre statuts. D'où deux garde-fous
 * supplémentaires (compteur de {@code trouble}, détection de streak du même statut).</p>
 *
 * @param maxIterations      nombre maximal d'appels LLM avant {@code KO} ; doit être &gt; 0
 * @param maxTroubleRetries  nombre maximal de statuts {@code trouble} cumulés tolérés
 * @param maxSameStatusStreak nombre d'itérations consécutives du même statut avant de
 *                            conclure à une boucle infinie ({@code KO}) ; doit être &gt; 0
 */
public record LoopConfig(int maxIterations, int maxTroubleRetries, int maxSameStatusStreak) {

    public LoopConfig {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations doit être > 0");
        }
        if (maxTroubleRetries < 0) {
            throw new IllegalArgumentException("maxTroubleRetries doit être >= 0");
        }
        if (maxSameStatusStreak <= 0) {
            throw new IllegalArgumentException("maxSameStatusStreak doit être > 0");
        }
    }

    /** Valeurs par défaut raisonnables pour le cockpit dev/devops (V1). */
    public static LoopConfig defaults() {
        return new LoopConfig(25, 3, 5);
    }
}
