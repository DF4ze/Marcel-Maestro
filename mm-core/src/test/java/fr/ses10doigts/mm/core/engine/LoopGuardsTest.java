package fr.ses10doigts.mm.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.agent.AgentStatus;
import org.junit.jupiter.api.Test;

/**
 * Vérifie le bornage et la détection de boucle infinie (étape 3, livrable 5 ; PB-07 cas 4).
 */
class LoopGuardsTest {

    @Test
    void borneLeNombreDIterations() {
        LoopGuards guards = new LoopGuards(new LoopConfig(2, 10, 10));

        assertTrue(guards.tryStartIteration());
        assertTrue(guards.tryStartIteration());
        assertFalse(guards.tryStartIteration(), "la 3e itération dépasse maxIterations=2");
        assertEquals(2, guards.iterations());
        assertEquals(GuardVerdict.KO_MAX_ITERATIONS, guards.maxIterationsVerdict());
    }

    @Test
    void koQuandTropDeTrouble() {
        LoopGuards guards = new LoopGuards(new LoopConfig(50, 2, 50));

        assertEquals(GuardVerdict.CONTINUE, guards.recordStatus(AgentStatus.TROUBLE));
        assertEquals(GuardVerdict.CONTINUE, guards.recordStatus(AgentStatus.TROUBLE));
        assertEquals(GuardVerdict.KO_TROUBLE_EXCEEDED, guards.recordStatus(AgentStatus.TROUBLE));
        assertEquals(3, guards.troubleCount());
    }

    @Test
    void koQuandMemeStatutRepeteTropDeFois() {
        LoopGuards guards = new LoopGuards(new LoopConfig(50, 50, 3));

        assertEquals(GuardVerdict.CONTINUE, guards.recordStatus(AgentStatus.RUNNING));
        assertEquals(GuardVerdict.CONTINUE, guards.recordStatus(AgentStatus.RUNNING));
        assertEquals(GuardVerdict.KO_INFINITE_LOOP, guards.recordStatus(AgentStatus.RUNNING));
    }

    @Test
    void unChangementDeStatutReinitialiseLaSerie() {
        LoopGuards guards = new LoopGuards(new LoopConfig(50, 50, 3));

        guards.recordStatus(AgentStatus.RUNNING);
        guards.recordStatus(AgentStatus.RUNNING);
        assertEquals(GuardVerdict.CONTINUE, guards.recordStatus(AgentStatus.BLOCKED),
                "le passage à un autre statut remet le compteur de série à zéro");
        assertEquals(GuardVerdict.CONTINUE, guards.recordStatus(AgentStatus.RUNNING));
    }
}
