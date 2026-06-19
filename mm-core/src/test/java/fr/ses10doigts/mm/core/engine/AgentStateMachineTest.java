package fr.ses10doigts.mm.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.ses10doigts.mm.core.agent.AgentStatus;
import org.junit.jupiter.api.Test;

/**
 * Vérifie le routage exhaustif par statut (étape 3, livrable 4 ; ADR-006).
 */
class AgentStateMachineTest {

    private final AgentStateMachine machine = new AgentStateMachine();

    @Test
    void routeChaqueStatutVersSonVerdict() {
        assertEquals(Routing.CONTINUE, machine.route(AgentStatus.RUNNING));
        assertEquals(Routing.CONTINUE, machine.route(AgentStatus.PENDING));
        assertEquals(Routing.TERMINATE_DONE, machine.route(AgentStatus.DONE));
        assertEquals(Routing.AWAIT_HUMAN, machine.route(AgentStatus.BLOCKED));
        assertEquals(Routing.RETRY_REINFORCED, machine.route(AgentStatus.TROUBLE));
        assertEquals(Routing.TERMINATE_KO, machine.route(AgentStatus.KO));
    }

    @Test
    void couvreTousLesStatuts() {
        // Si un statut est ajouté à l'enum sans cas de routage, le switch exhaustif de
        // AgentStateMachine ne compilerait plus : ce test documente la garantie.
        for (AgentStatus status : AgentStatus.values()) {
            machine.route(status); // ne doit jamais lever
        }
    }

    @Test
    void rejetteUnStatutNull() {
        assertThrows(NullPointerException.class, () -> machine.route(null));
    }
}
