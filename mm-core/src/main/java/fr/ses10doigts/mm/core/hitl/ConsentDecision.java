package fr.ses10doigts.mm.core.hitl;

/**
 * Décision de consentement humain face à une demande HITL (ADR-005).
 *
 * <p>Quatre niveaux d'autorisation + le refus. {@code ALLOW_ONCE} et
 * {@code ALLOW_SESSION} sont gérés en cache mémoire (étape 4) ; {@code ALLOW_PROJECT}
 * et {@code ALLOW_ALWAYS} sont persistés en mémoire factuelle (étape 5).</p>
 */
public enum ConsentDecision {
    /** Autorise cet appel uniquement. */
    ALLOW_ONCE,
    /** Autorise pour toute la session courante. */
    ALLOW_SESSION,
    /** Autorise pour tout le projet (persisté, étape 5). */
    ALLOW_PROJECT,
    /** Autorise toujours (persisté, étape 5). */
    ALLOW_ALWAYS,
    /** Refuse l'action. */
    DENY
}
