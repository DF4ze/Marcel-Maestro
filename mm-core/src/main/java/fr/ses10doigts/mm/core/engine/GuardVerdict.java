package fr.ses10doigts.mm.core.engine;

/**
 * Verdict des garde-fous après enregistrement d'une itération (étape 3, livrable 5).
 *
 * <p>Soit la boucle peut continuer, soit un garde-fou impose un arrêt en {@code KO} avec
 * une cause précise (journalisée et reportée dans {@link AgentOutcome}).</p>
 */
public enum GuardVerdict {

    /** Aucune borne franchie : la boucle peut continuer. */
    CONTINUE(null),
    /** Borne {@code maxIterations} atteinte. */
    KO_MAX_ITERATIONS("maxIterations atteint"),
    /** Trop de statuts {@code trouble} cumulés. */
    KO_TROUBLE_EXCEEDED("maxTroubleRetries dépassé"),
    /** Même statut répété trop de fois d'affilée : boucle infinie présumée. */
    KO_INFINITE_LOOP("boucle infinie détectée (même statut répété)");

    private final String reason;

    GuardVerdict(String reason) {
        this.reason = reason;
    }

    /** {@code true} si ce verdict impose un arrêt en {@code KO}. */
    public boolean isTerminal() {
        return this != CONTINUE;
    }

    /** Raison lisible de l'arrêt, ou {@code null} pour {@link #CONTINUE}. */
    public String reason() {
        return reason;
    }
}
