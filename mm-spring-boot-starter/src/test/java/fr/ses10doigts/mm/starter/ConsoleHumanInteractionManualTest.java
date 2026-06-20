package fr.ses10doigts.mm.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Test manuel du canal console réel.
 *
 * <p>N'est jamais exécuté par {@code mvn test}. Pour le lancer manuellement :</p>
 * <pre>
 * mvn -pl mm-spring-boot-starter test -Dtest=ConsoleHumanInteractionManualTest -Dmm.manual.hitl=true
 * </pre>
 *
 * <p>Le test attend que l'opérateur saisisse {@code ALLOW_ONCE} sur stdin.</p>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "mm.manual.hitl", matches = "true")
class ConsoleHumanInteractionManualTest {

    private static final AgentContext CTX =
            AgentContext.of("default", "manual-project", "manual-conversation", "manual-task");

    /**
     * Vérifie manuellement que le canal console lit bien stdin et retourne la décision attendue.
     */
    @Test
    void ask_surConsoleReelle_allowOnce() {
        ConsoleHumanInteraction console = new ConsoleHumanInteraction();

        ConsentDecision decision = console.ask(
                new HitlRequest("Test manuel HITL — saisissez ALLOW_ONCE", RiskLevel.HIGH, CTX));

        assertEquals(ConsentDecision.ALLOW_ONCE, decision);
    }
}
