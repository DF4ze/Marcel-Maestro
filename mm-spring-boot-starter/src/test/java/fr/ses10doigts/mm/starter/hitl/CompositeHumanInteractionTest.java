package fr.ses10doigts.mm.starter.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.HumanInteraction;
import fr.ses10doigts.mm.core.hitl.NotificationLevel;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests du multiplexeur multi-canal {@link CompositeHumanInteraction}.
 */
class CompositeHumanInteractionTest {

    private static final AgentContext CTX = AgentContext.of("default", "p1", "c1", "t1");
    private static final HitlRequest REQUEST = new HitlRequest("Autoriser ?", RiskLevel.HIGH, CTX);
    private static final AgentNotification NOTIFICATION =
            new AgentNotification("Titre", "Message", NotificationLevel.INFO, CTX);

    @Test
    void notifyBroadcastsToAllChannels() {
        List<String> log = Collections.synchronizedList(new ArrayList<>());

        HumanInteraction ch1 = new StubChannel("ch1", log, ConsentDecision.DENY);
        HumanInteraction ch2 = new StubChannel("ch2", log, ConsentDecision.DENY);

        CompositeHumanInteraction composite = new CompositeHumanInteraction(
                List.of(ch1, ch2), "race");

        composite.notify(NOTIFICATION);

        assertTrue(log.contains("ch1:notify"), "ch1 doit recevoir le notify");
        assertTrue(log.contains("ch2:notify"), "ch2 doit recevoir le notify");
    }

    @Test
    void notifyContinuesIfOneChannelFails() {
        List<String> log = Collections.synchronizedList(new ArrayList<>());

        HumanInteraction failing = new HumanInteraction() {
            @Override public ConsentDecision ask(HitlRequest r) { return ConsentDecision.DENY; }
            @Override public void notify(AgentNotification n) { throw new RuntimeException("boom"); }
        };
        HumanInteraction ch2 = new StubChannel("ch2", log, ConsentDecision.DENY);

        CompositeHumanInteraction composite = new CompositeHumanInteraction(
                List.of(failing, ch2), "race");

        composite.notify(NOTIFICATION);

        assertTrue(log.contains("ch2:notify"), "ch2 doit recevoir le notify malgré l'erreur de ch1");
    }

    @Test
    void askSingleChannelPassthrough() {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        HumanInteraction ch1 = new StubChannel("ch1", log, ConsentDecision.ALLOW_SESSION);

        CompositeHumanInteraction composite = new CompositeHumanInteraction(
                List.of(ch1), "race");

        ConsentDecision result = composite.ask(REQUEST);
        assertEquals(ConsentDecision.ALLOW_SESSION, result);
    }

    @Test
    void askRaceModeFirstResponderWins() throws InterruptedException {
        CountDownLatch slowStarted = new CountDownLatch(1);
        AtomicBoolean slowCancelled = new AtomicBoolean(false);

        // Canal rapide : répond immédiatement
        CancellableHumanInteraction fast = new CancellableHumanInteraction() {
            @Override public ConsentDecision ask(HitlRequest r) { return ConsentDecision.ALLOW_ONCE; }
            @Override public void notify(AgentNotification n) {}
            @Override public void cancelPendingAsk() {}
        };

        // Canal lent : bloque 10s (sera annulé)
        CancellableHumanInteraction slow = new CancellableHumanInteraction() {
            @Override
            public ConsentDecision ask(HitlRequest r) {
                slowStarted.countDown();
                try { Thread.sleep(10_000); } catch (InterruptedException e) { /* ok */ }
                return ConsentDecision.DENY;
            }
            @Override public void notify(AgentNotification n) {}
            @Override public void cancelPendingAsk() { slowCancelled.set(true); }
        };

        CompositeHumanInteraction composite = new CompositeHumanInteraction(
                List.of(fast, slow), "race");

        ConsentDecision result = composite.ask(REQUEST);

        assertEquals(ConsentDecision.ALLOW_ONCE, result, "Le canal rapide doit gagner");

        // Laisser le temps au Composite d'annuler le perdant
        Thread.sleep(200);
        assertTrue(slowCancelled.get(), "Le canal lent doit être annulé");
    }

    @Test
    void askNamedChannelMode() {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        HumanInteraction console = new ConsoleStubChannel(log, ConsentDecision.ALLOW_PROJECT);
        HumanInteraction telegram = new TelegramStubChannel(log, ConsentDecision.DENY);

        CompositeHumanInteraction composite = new CompositeHumanInteraction(
                List.of(console, telegram), "console");

        ConsentDecision result = composite.ask(REQUEST);
        assertEquals(ConsentDecision.ALLOW_PROJECT, result);
    }

    // ── Stubs ─────────────────────────────────────────────────────────────

    /**
     * Canal stub générique pour les tests.
     */
    private static class StubChannel implements CancellableHumanInteraction {
        protected final List<String> log;
        protected final ConsentDecision decision;
        protected final String label;

        StubChannel(String label, List<String> log, ConsentDecision decision) {
            this.label = label;
            this.log = log;
            this.decision = decision;
        }

        @Override public ConsentDecision ask(HitlRequest r) {
            log.add(label + ":ask");
            return decision;
        }

        @Override public void notify(AgentNotification n) {
            log.add(label + ":notify");
        }

        @Override public void cancelPendingAsk() {
            log.add(label + ":cancel");
        }
    }

    /**
     * Stub dont le nom de classe contient "Console" pour le matching par nom.
     */
    private static class ConsoleStubChannel extends StubChannel {
        ConsoleStubChannel(List<String> log, ConsentDecision decision) {
            super("console", log, decision);
        }
    }

    /**
     * Stub dont le nom de classe contient "Telegram" pour le matching par nom.
     */
    private static class TelegramStubChannel extends StubChannel {
        TelegramStubChannel(List<String> log, ConsentDecision decision) {
            super("telegram", log, decision);
        }
    }
}
