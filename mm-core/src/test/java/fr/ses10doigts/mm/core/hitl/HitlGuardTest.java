package fr.ses10doigts.mm.core.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests du {@link HitlGuard} avec un mock scripté de {@link HumanInteraction}.
 */
class HitlGuardTest {

    private static final AgentContext CTX =
            AgentContext.of("default", "p1", "c1", "t1");

    // ── Mock scripté ─────────────────────────────────────────────────

    /**
     * Implémentation de test qui retourne des décisions pré-scriptées et enregistre
     * les demandes reçues.
     */
    static class ScriptedHumanInteraction implements HumanInteraction {
        private final List<ConsentDecision> decisions = new ArrayList<>();
        private final List<HitlRequest> receivedRequests = new ArrayList<>();
        private int index = 0;

        ScriptedHumanInteraction respond(ConsentDecision... ds) {
            decisions.addAll(List.of(ds));
            return this;
        }

        List<HitlRequest> requests() { return receivedRequests; }

        @Override
        public ConsentDecision ask(HitlRequest request) {
            receivedRequests.add(request);
            if (index < decisions.size()) {
                return decisions.get(index++);
            }
            return decisions.isEmpty() ? ConsentDecision.DENY : decisions.getLast();
        }

        @Override
        public void notify(AgentNotification notification) { }
    }

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    void risqueSousSeuil_pasDeDemandeHumaine() {
        var mock = new ScriptedHumanInteraction();
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), mock);

        HitlVerdict verdict = guard.check("read_file", null, RiskLevel.LOW, null, CTX);

        assertTrue(verdict.allowed());
        assertNull(verdict.decision());
        assertTrue(mock.requests().isEmpty(), "pas d'appel à ask()");
    }

    @Test
    void risqueAuSeuil_demandeHumaine_allowOnce() {
        var mock = new ScriptedHumanInteraction().respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), mock);

        HitlVerdict verdict = guard.check("deploy", null, RiskLevel.MEDIUM, null, CTX);

        assertTrue(verdict.allowed());
        assertEquals(ConsentDecision.ALLOW_ONCE, verdict.decision());
        assertEquals(1, mock.requests().size());
    }

    @Test
    void deny_verdictRefuse() {
        var mock = new ScriptedHumanInteraction().respond(ConsentDecision.DENY);
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), mock);

        HitlVerdict verdict = guard.check("deploy", null, RiskLevel.HIGH, null, CTX);

        assertFalse(verdict.allowed());
        assertEquals(ConsentDecision.DENY, verdict.decision());
    }

    @Test
    void allowSession_cacheNeRedemandePas() {
        var mock = new ScriptedHumanInteraction().respond(ConsentDecision.ALLOW_LARGE_SESSION);
        ConsentCache cache = new ConsentCache();
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), cache, mock);

        // Premier appel → demande
        HitlVerdict v1 = guard.check("deploy", null, RiskLevel.HIGH, null, CTX);
        assertTrue(v1.allowed());
        assertEquals(1, mock.requests().size());

        // Deuxième appel → cache, pas de demande
        HitlVerdict v2 = guard.check("deploy", null, RiskLevel.HIGH, null, CTX);
        assertTrue(v2.allowed());
        assertEquals(ConsentDecision.ALLOW_LARGE_SESSION, v2.decision());
        assertEquals(1, mock.requests().size(), "pas de deuxième appel à ask()");
    }

    @Test
    void allowOnce_redemandeAChaqueFois() {
        var mock = new ScriptedHumanInteraction()
                .respond(ConsentDecision.ALLOW_ONCE, ConsentDecision.ALLOW_ONCE);
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), mock);

        guard.check("deploy", null, RiskLevel.HIGH, null, CTX);
        guard.check("deploy", null, RiskLevel.HIGH, null, CTX);

        assertEquals(2, mock.requests().size(), "ALLOW_ONCE ne cache pas");
    }

    @Test
    void allowProject_cacheComeSession_coutureEtape5() {
        var mock = new ScriptedHumanInteraction().respond(ConsentDecision.ALLOW_LARGE_PROJECT);
        ConsentCache cache = new ConsentCache();
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), cache, mock);

        guard.check("deploy", null, RiskLevel.CRITICAL, null, CTX);
        guard.check("deploy", null, RiskLevel.CRITICAL, null, CTX);

        assertEquals(1, mock.requests().size(), "ALLOW_LARGE_PROJECT caché comme session");
        assertEquals(ConsentDecision.ALLOW_LARGE_PROJECT, cache.lookup("deploy").orElse(null));
    }

    @Test
    void allowAlways_cacheComeSession_coutureEtape5() {
        var mock = new ScriptedHumanInteraction().respond(ConsentDecision.ALLOW_LARGE_ALWAYS);
        ConsentCache cache = new ConsentCache();
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), cache, mock);

        guard.check("deploy", null, RiskLevel.CRITICAL, null, CTX);
        guard.check("deploy", null, RiskLevel.CRITICAL, null, CTX);

        assertEquals(1, mock.requests().size(), "ALLOW_LARGE_ALWAYS caché comme session");
    }

    @Test
    void outilsDifferents_demandeSeparee() {
        var mock = new ScriptedHumanInteraction()
                .respond(ConsentDecision.ALLOW_LARGE_SESSION, ConsentDecision.ALLOW_LARGE_SESSION);
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), mock);

        guard.check("deploy", null, RiskLevel.HIGH, null, CTX);
        guard.check("restart", null, RiskLevel.HIGH, null, CTX);

        assertEquals(2, mock.requests().size(), "outils différents = demandes séparées");
    }

    @Test
    void questionContientNomOutilEtRisque() {
        var mock = new ScriptedHumanInteraction().respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard guard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), mock);

        guard.check("service_management", "Gère les services système", RiskLevel.CRITICAL, null, CTX);

        HitlRequest req = mock.requests().getFirst();
        assertTrue(req.question().contains("service_management"));
        assertEquals(RiskLevel.CRITICAL, req.riskLevel());
    }
}
