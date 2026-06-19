package fr.ses10doigts.mm.core.tool;

/**
 * Niveau de risque d'un {@link AgentTool}, axe du consentement (HITL).
 *
 * <p>Orthogonal à l'autorisation (quels outils un agent possède). Pilote le
 * garde-fou humain (étape 4) : {@code LOW} = exécution directe ;
 * {@code MEDIUM}/{@code HIGH}/{@code CRITICAL} = validation requise (configurable).</p>
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
