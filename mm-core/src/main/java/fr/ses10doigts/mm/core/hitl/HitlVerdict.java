package fr.ses10doigts.mm.core.hitl;

/**
 * Verdict du {@link HitlGuard} après évaluation d'une demande de consentement
 * (étape 4, livrable 2).
 *
 * <p>Trois cas possibles :</p>
 * <ul>
 *   <li>{@link #noConsentNeeded()} — le niveau de risque ne requiert pas de
 *       validation humaine</li>
 *   <li>{@link #allowed(ConsentDecision)} — consentement accordé (depuis le cache ou
 *       après demande)</li>
 *   <li>{@link #denied(String)} — consentement refusé par l'humain</li>
 * </ul>
 *
 * @param allowed  {@code true} si l'exécution peut continuer
 * @param decision la décision humaine (peut être {@code null} si pas de demande nécessaire)
 * @param reason   justification lisible du verdict
 */
public record HitlVerdict(boolean allowed, ConsentDecision decision, String reason) {

    /**
     * L'outil ne nécessite pas de consentement (risque sous le seuil).
     */
    public static HitlVerdict noConsentNeeded() {
        return new HitlVerdict(true, null, "risk level below threshold — no consent needed");
    }

    /**
     * Consentement accordé (par l'humain ou depuis le cache).
     */
    public static HitlVerdict allowed(ConsentDecision decision) {
        return new HitlVerdict(true, decision, "consent granted: " + decision);
    }

    /**
     * Consentement refusé par l'humain.
     */
    public static HitlVerdict denied(String reason) {
        return new HitlVerdict(false, ConsentDecision.DENY, reason != null ? reason : "denied by user");
    }
}
