package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.NotificationLevel;
import fr.ses10doigts.mm.starter.hitl.CancellableHumanInteraction;
import fr.ses10doigts.telegrambots.model.TelegramButtonView;
import fr.ses10doigts.telegrambots.model.TelegramMessageFormat;
import fr.ses10doigts.telegrambots.model.TelegramView;
import fr.ses10doigts.telegrambots.service.sender.SimpleTelegramSender;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Adaptateur Telegram du port {@link fr.ses10doigts.mm.core.hitl.HumanInteraction}
 * (étape 8, livrables 4 et 5).
 *
 * <p>Réutilise le module {@code telegram-bots-mvc} existant via
 * {@link SimpleTelegramSender}. Un seul bot, un seul chatId (ADR-016).
 * Le contexte (quel agent, quelle tâche) est inclus dans le message.</p>
 *
 * <p>{@link #notify(AgentNotification)} envoie un message texte formaté.
 * {@link #ask(HitlRequest)} envoie un message avec 5 boutons inline
 * (les 5 valeurs de {@link ConsentDecision}) et bloque jusqu'à la réponse
 * via callback ou timeout.</p>
 *
 * <p><strong>Limitation V1 :</strong> un seul ask() peut être en attente à la fois
 * côté Telegram (les callbacks utilisent des valeurs fixes). Si un second ask()
 * arrive pendant qu'un premier est en attente, le premier est annulé (DENY).
 * Cette limitation est acceptable pour un seul utilisateur (ADR-016).</p>
 */
@Slf4j
public class TelegramHumanInteraction implements CancellableHumanInteraction {

    /** Préfixe des callbackData HITL (valeurs fixes pour le match exact @CallbackQuery). */
    static final String CB_PREFIX = "hitl_";
    static final String CB_ALLOW_ONCE = CB_PREFIX + "allow_once";
    static final String CB_ALLOW_SESSION = CB_PREFIX + "allow_session";
    static final String CB_ALLOW_PROJECT = CB_PREFIX + "allow_project";
    static final String CB_ALLOW_ALWAYS = CB_PREFIX + "allow_always";
    static final String CB_DENY = CB_PREFIX + "deny";

    private static final Map<NotificationLevel, String> LEVEL_EMOJI = Map.of(
            NotificationLevel.INFO, "ℹ️",
            NotificationLevel.SUCCESS, "✅",
            NotificationLevel.WARNING, "⚠️",
            NotificationLevel.ERROR, "❌"
    );

    private final SimpleTelegramSender sender;
    private final String botId;
    private final Long chatId;
    private final int askTimeoutSeconds;

    /**
     * Future de la demande HITL en attente. Un seul à la fois côté Telegram.
     * Un nouveau ask() annule le précédent s'il est encore en attente.
     */
    private final AtomicReference<CompletableFuture<ConsentDecision>> pendingAsk =
            new AtomicReference<>();

    /**
     * Construit un adaptateur Telegram.
     *
     * @param sender            sender explicite (botId en paramètre à chaque appel)
     * @param botId             identifiant du bot MM dans le registre telegram-bots-mvc
     * @param chatId            ID du chat Telegram pour les messages
     * @param askTimeoutSeconds timeout en secondes pour ask()
     */
    public TelegramHumanInteraction(SimpleTelegramSender sender, String botId,
                                     Long chatId, int askTimeoutSeconds) {
        this.sender = sender;
        this.botId = botId;
        this.chatId = chatId;
        this.askTimeoutSeconds = askTimeoutSeconds;
        log.info("TelegramHumanInteraction initialisé — botId={}, chatId={}, timeout={}s",
                botId, chatId, askTimeoutSeconds);
    }

    /**
     * Envoie une notification formatée sur Telegram. Le message inclut le contexte
     * (agent, tâche) conformément à ADR-016.
     *
     * @param notification notification à pousser
     */
    @Override
    public void notify(AgentNotification notification) {
        String emoji = LEVEL_EMOJI.getOrDefault(notification.level(), "ℹ️");
        String ctx = formatContext(notification);

        String text = String.format("%s *%s*\n%s\n_%s_",
                emoji,
                notification.title(),
                notification.message(),
                ctx);

        try {
            sender.sendMarkdownMessage(botId, chatId, text);
            log.info("Telegram notify() envoyé — level={}, title={}",
                    notification.level(), notification.title());
        } catch (Exception e) {
            log.info("Telegram notify() échoué : {}", e.getMessage());
        }
    }

    /**
     * Envoie une demande HITL avec boutons inline et bloque jusqu'à la réponse.
     *
     * <p>Si une demande est déjà en attente, elle est annulée (DENY) avant
     * d'envoyer la nouvelle.</p>
     *
     * @param request demande de consentement
     * @return décision de l'utilisateur, ou {@code DENY} en cas de timeout/erreur
     */
    @Override
    public ConsentDecision ask(HitlRequest request) {
        CompletableFuture<ConsentDecision> future = new CompletableFuture<>();

        // Annuler une éventuelle demande précédente
        CompletableFuture<ConsentDecision> previous = pendingAsk.getAndSet(future);
        if (previous != null && !previous.isDone()) {
            previous.complete(ConsentDecision.DENY);
            log.info("Telegram ask() — demande précédente annulée (remplacée)");
        }

        try {
            sendAskMessage(request);
            ConsentDecision decision = future.get(askTimeoutSeconds, TimeUnit.SECONDS);
            log.info("Telegram ask() — réponse reçue : {}", decision);
            return decision;

        } catch (TimeoutException e) {
            log.info("Telegram ask() — timeout après {}s, retour DENY", askTimeoutSeconds);
            sender.sendMessage(botId, chatId, "⏱ Demande HITL expirée (timeout).");
            return ConsentDecision.DENY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Telegram ask() — interrompu, retour DENY");
            return ConsentDecision.DENY;
        } catch (Exception e) {
            log.info("Telegram ask() — erreur, retour DENY : {}", e.getMessage());
            return ConsentDecision.DENY;
        } finally {
            pendingAsk.compareAndSet(future, null);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Complète le future en attente pour débloquer le thread dans {@link #ask}.</p>
     */
    @Override
    public void cancelPendingAsk() {
        CompletableFuture<ConsentDecision> current = pendingAsk.getAndSet(null);
        if (current != null && !current.isDone()) {
            current.completeExceptionally(new InterruptedException("Annulé par un autre canal"));
            log.debug("Telegram ask() — demande annulée par un autre canal");
        }
    }

    /**
     * Résout la demande HITL en attente. Appelé par le {@link TelegramMmController}
     * quand l'utilisateur clique sur un bouton inline.
     *
     * @param decision décision de l'utilisateur
     * @return {@code true} si une demande était en attente et a été résolue
     */
    public boolean resolveAsk(ConsentDecision decision) {
        CompletableFuture<ConsentDecision> current = pendingAsk.getAndSet(null);
        if (current != null && !current.isDone()) {
            current.complete(decision);
            log.info("Telegram HITL résolu — decision={}", decision);
            return true;
        }
        log.debug("Telegram HITL — aucune demande en attente à résoudre");
        return false;
    }

    /**
     * Envoie le message HITL avec les 5 boutons inline.
     */
    private void sendAskMessage(HitlRequest request) {
        String text = String.format("⚠️ *Validation requise* \\[%s\\]\n\n%s",
                request.riskLevel(),
                request.question());

        TelegramView view = TelegramView.builder()
                .text(text)
                .format(TelegramMessageFormat.MARKDOWN)
                .buttons(List.of(
                        List.of(
                                new TelegramButtonView("✅ Une fois", CB_ALLOW_ONCE),
                                new TelegramButtonView("📋 Session", CB_ALLOW_SESSION)
                        ),
                        List.of(
                                new TelegramButtonView("📁 Projet", CB_ALLOW_PROJECT),
                                new TelegramButtonView("🔓 Toujours", CB_ALLOW_ALWAYS)
                        ),
                        List.of(
                                new TelegramButtonView("❌ Refuser", CB_DENY)
                        )
                ))
                .build();

        sender.sendView(botId, chatId, view);
        log.info("Telegram ask() envoyé — riskLevel={}", request.riskLevel());
    }

    /**
     * Formate le contexte agent/tâche pour l'inclusion dans le message (ADR-016).
     */
    private static String formatContext(AgentNotification notification) {
        if (notification.ctx() == null) {
            return "";
        }
        return String.format("agent=%s task=%s",
                notification.ctx().conversationId(),
                notification.ctx().taskId());
    }
}
