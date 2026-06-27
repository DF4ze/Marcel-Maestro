package fr.ses10doigts.mm.app.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.NotificationLevel;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.telegrambots.model.TelegramView;
import fr.ses10doigts.telegrambots.service.sender.SimpleTelegramSender;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'adaptateur Telegram du port HumanInteraction.
 */
class TelegramHumanInteractionTest {

    private SimpleTelegramSender sender;
    private TelegramHumanInteraction telegram;

    private static final AgentContext CTX = AgentContext.of("default", "p1", "c1", "t1");

    @BeforeEach
    void setUp() {
        sender = mock(SimpleTelegramSender.class);
        doNothing().when(sender).sendMarkdownMessage(anyString(), anyLong(), anyString());
        doNothing().when(sender).sendMessage(anyString(), anyLong(), anyString());
        doNothing().when(sender).sendView(anyString(), anyLong(), any(TelegramView.class));

        telegram = new TelegramHumanInteraction(sender, "mm-bot", 12345L, 5, null);
    }

    @Test
    void notifySendsPlainMessage() {
        // notify() utilise volontairement sendMessage (texte brut) et NON sendMarkdownMessage :
        // le contenu peut venir du LLM et contenir des caractères spéciaux (*, _, […) qui
        // casseraient le parser Markdown v1 de Telegram. Voir TelegramHumanInteraction#notify.
        AgentNotification notification = new AgentNotification(
                "Build terminé", "Succès en 42s", NotificationLevel.SUCCESS, CTX);

        telegram.notify(notification);

        verify(sender).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    void askSendsViewAndBlocksUntilResolved() throws Exception {
        HitlRequest request = new HitlRequest("Exécuter build ?", RiskLevel.HIGH, CTX);

        // Lancer ask() dans un thread séparé
        CompletableFuture<ConsentDecision> result = CompletableFuture.supplyAsync(
                () -> telegram.ask(request));

        // Attendre que le message soit envoyé
        Thread.sleep(200);
        verify(sender).sendView(anyString(), anyLong(), any(TelegramView.class));

        // Simuler le callback utilisateur
        boolean resolved = telegram.resolveAsk(ConsentDecision.ALLOW_LARGE_SESSION);
        assertTrue(resolved, "La demande doit être résolue");

        ConsentDecision decision = result.get(2, TimeUnit.SECONDS);
        assertEquals(ConsentDecision.ALLOW_LARGE_SESSION, decision);
    }

    @Test
    @Tag("very-slow")
    void askTimesOutToDeny() {
        // Timeout = 5s, mais on ne résout jamais → la config du test a 5s timeout
        // Pour accélérer le test, on crée une instance avec un timeout très court
        TelegramHumanInteraction shortTimeout = new TelegramHumanInteraction(
                sender, "mm-bot", 12345L, 1, null);

        HitlRequest request = new HitlRequest("Timeout test", RiskLevel.MEDIUM, CTX);
        ConsentDecision decision = shortTimeout.ask(request);

        assertEquals(ConsentDecision.DENY, decision, "Timeout doit retourner DENY");
    }

    @Test
    void cancelPendingAskUnblocks() throws Exception {
        HitlRequest request = new HitlRequest("Cancel test", RiskLevel.HIGH, CTX);

        CompletableFuture<ConsentDecision> result = CompletableFuture.supplyAsync(
                () -> telegram.ask(request));

        Thread.sleep(200);

        // Annuler depuis un autre canal
        telegram.cancelPendingAsk();

        ConsentDecision decision = result.get(2, TimeUnit.SECONDS);
        assertEquals(ConsentDecision.DENY, decision, "Annulation doit retourner DENY");
    }

    @Test
    void resolveWithoutPendingReturnsFalse() {
        boolean resolved = telegram.resolveAsk(ConsentDecision.ALLOW_ONCE);
        assertFalse(resolved, "Pas de demande en attente");
    }
}
