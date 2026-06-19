package fr.ses10doigts.mm.core.hitl;

import fr.ses10doigts.mm.core.tool.RiskLevel;

/**
 * Politique de consentement HITL : mappe un {@link RiskLevel} vers la nécessité ou non
 * d'une validation humaine (étape 4, livrable 1 ; ADR-005).
 *
 * <p>Tout outil dont le {@code riskLevel} est <strong>supérieur ou égal</strong> au seuil
 * ({@code threshold}) requiert un consentement via {@link HumanInteraction#ask}. En dessous
 * du seuil : exécution directe, pas de demande.</p>
 *
 * <p>Le seuil par défaut est {@link RiskLevel#MEDIUM} : {@code LOW} passe directement,
 * {@code MEDIUM}, {@code HIGH} et {@code CRITICAL} demandent confirmation.</p>
 *
 * <p>Pur noyau, aucune dépendance infrastructure.</p>
 */
public final class HitlPolicy {

    private final RiskLevel threshold;

    /**
     * @param threshold niveau de risque à partir duquel le consentement est requis
     *                  (inclus) ; ne doit pas être {@code null}
     */
    public HitlPolicy(RiskLevel threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException("threshold must not be null");
        }
        this.threshold = threshold;
    }

    /**
     * Politique par défaut : seuil à {@link RiskLevel#MEDIUM}.
     */
    public static HitlPolicy defaults() {
        return new HitlPolicy(RiskLevel.MEDIUM);
    }

    /**
     * @return {@code true} si le niveau de risque atteint ou dépasse le seuil configuré
     */
    public boolean requiresConsent(RiskLevel level) {
        if (level == null) {
            return false;
        }
        return level.ordinal() >= threshold.ordinal();
    }

    /**
     * @return le seuil configuré
     */
    public RiskLevel threshold() {
        return threshold;
    }
}
