package fr.ses10doigts.mm.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.NotificationLevel;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsoleHumanInteractionTest {

    private static final AgentContext CTX =
            AgentContext.of("default", "p1", "c1", "t1");

    private ConsoleHumanInteraction withInput(String... lines) {
        String joined = String.join("\n", lines) + "\n";
        var in = new ByteArrayInputStream(joined.getBytes(StandardCharsets.UTF_8));
        return new ConsoleHumanInteraction(in, new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void ask_allowOnce() {
        var console = withInput("ALLOW_ONCE");

        ConsentDecision decision = console.ask(
                new HitlRequest("Exécuter deploy ?", RiskLevel.HIGH, CTX));

        assertEquals(ConsentDecision.ALLOW_ONCE, decision);
    }

    @Test
    void ask_allowSession() {
        var console = withInput("allow_session"); // case insensitive

        ConsentDecision decision = console.ask(
                new HitlRequest("Exécuter deploy ?", RiskLevel.HIGH, CTX));

        assertEquals(ConsentDecision.ALLOW_SESSION, decision);
    }

    @Test
    void ask_deny() {
        var console = withInput("DENY");

        ConsentDecision decision = console.ask(
                new HitlRequest("Exécuter deploy ?", RiskLevel.HIGH, CTX));

        assertEquals(ConsentDecision.DENY, decision);
    }

    @Test
    void ask_entreeInvalidePuisValide_boucle() {
        var console = withInput("nimportequoi", "ALLOW_ALWAYS");

        ConsentDecision decision = console.ask(
                new HitlRequest("Exécuter deploy ?", RiskLevel.CRITICAL, CTX));

        assertEquals(ConsentDecision.ALLOW_ALWAYS, decision);
    }

    @Test
    void ask_stdinFerme_denyParDefaut() {
        // Flux vide = EOF immédiat
        var in = new ByteArrayInputStream(new byte[0]);
        var console = new ConsoleHumanInteraction(in, new PrintStream(new ByteArrayOutputStream()));

        ConsentDecision decision = console.ask(
                new HitlRequest("Exécuter deploy ?", RiskLevel.HIGH, CTX));

        assertEquals(ConsentDecision.DENY, decision);
    }

    @Test
    void notify_ecritSurStdout() {
        var out = new ByteArrayOutputStream();
        var console = new ConsoleHumanInteraction(System.in, new PrintStream(out));

        console.notify(new AgentNotification(
                "Build terminé", "Le build Maven est vert", NotificationLevel.SUCCESS, CTX));

        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Build terminé"), output);
        assertTrue(output.contains("SUCCESS"), output);
    }
}
