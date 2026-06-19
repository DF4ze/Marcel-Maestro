package fr.ses10doigts.mm.core.hitl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.tool.RiskLevel;
import org.junit.jupiter.api.Test;

class HitlPolicyTest {

    @Test
    void seuilParDefaut_medium_lowPasseDirectement() {
        HitlPolicy policy = HitlPolicy.defaults();

        assertFalse(policy.requiresConsent(RiskLevel.LOW));
        assertTrue(policy.requiresConsent(RiskLevel.MEDIUM));
        assertTrue(policy.requiresConsent(RiskLevel.HIGH));
        assertTrue(policy.requiresConsent(RiskLevel.CRITICAL));
    }

    @Test
    void seuilHigh_mediumPasseDirectement() {
        HitlPolicy policy = new HitlPolicy(RiskLevel.HIGH);

        assertFalse(policy.requiresConsent(RiskLevel.LOW));
        assertFalse(policy.requiresConsent(RiskLevel.MEDIUM));
        assertTrue(policy.requiresConsent(RiskLevel.HIGH));
        assertTrue(policy.requiresConsent(RiskLevel.CRITICAL));
    }

    @Test
    void seuilCritical_seulCriticalDemande() {
        HitlPolicy policy = new HitlPolicy(RiskLevel.CRITICAL);

        assertFalse(policy.requiresConsent(RiskLevel.LOW));
        assertFalse(policy.requiresConsent(RiskLevel.MEDIUM));
        assertFalse(policy.requiresConsent(RiskLevel.HIGH));
        assertTrue(policy.requiresConsent(RiskLevel.CRITICAL));
    }

    @Test
    void seuilLow_toutDemandeConsentement() {
        HitlPolicy policy = new HitlPolicy(RiskLevel.LOW);

        assertTrue(policy.requiresConsent(RiskLevel.LOW));
        assertTrue(policy.requiresConsent(RiskLevel.MEDIUM));
        assertTrue(policy.requiresConsent(RiskLevel.HIGH));
        assertTrue(policy.requiresConsent(RiskLevel.CRITICAL));
    }

    @Test
    void nullRiskLevel_pasDeConsentement() {
        assertFalse(HitlPolicy.defaults().requiresConsent(null));
    }
}
