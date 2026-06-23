package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.telegrambots.model.TelegramUpdateContext;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.CallbackQuery;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Chat;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Command;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.TelegramController;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Contrôleur Telegram pour le pilotage de Marcel Maestro (étape 8, livrable 5).
 *
 * <p>Gère les commandes {@code /stop} et {@code /status}, ainsi que les callbacks
 * des boutons inline HITL (5 handlers à valeur fixe, un par {@link ConsentDecision}).
 * Le {@link Dispatcher} est injecté via {@link ObjectProvider} car il peut être absent
 * si aucune {@link fr.ses10doigts.mm.core.orchestration.AgentFactory} n'est déclarée.</p>
 *
 * <p>Le handler {@link #chat(TelegramUpdateContext)} soumet le message de l'utilisateur
 * dans la {@link TaskQueue} comme un {@code USER_REQUEST} destiné au cortex. La réponse
 * est renvoyée de façon asynchrone via {@link TelegramHumanInteraction#notify} quand
 * le Dispatcher clôture la tâche.</p>
 */
@TelegramController
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
@Slf4j
public class TelegramMmController {

    private final ObjectProvider<Dispatcher> dispatcherProvider;
    private final ObjectProvider<TelegramHumanInteraction> telegramProvider;
    private final TaskQueue taskQueue;

    /**
     * Construit le contrôleur Telegram MM.
     *
     * @param dispatcherProvider provider du Dispatcher (optionnel)
     * @param telegramProvider   provider du TelegramHumanInteraction (optionnel)
     * @param taskQueue          file de tâches (toujours présente si le starter est chargé)
     */
    public TelegramMmController(ObjectProvider<Dispatcher> dispatcherProvider,
                                 ObjectProvider<TelegramHumanInteraction> telegramProvider,
                                 TaskQueue taskQueue) {
        this.dispatcherProvider = dispatcherProvider;
        this.telegramProvider = telegramProvider;
        this.taskQueue = taskQueue;
    }

    // ── Chat ────────────────────────────────────────────────────────

    /**
     * Reçoit un message libre de l'utilisateur, le soumet au cortex via la
     * {@link TaskQueue} et retourne un accusé de réception immédiat.
     *
     * <p>La réponse effective sera poussée de manière asynchrone par
     * {@link TelegramHumanInteraction#notify} quand le Dispatcher clôture la tâche.</p>
     *
     * @param ctx contexte de l'update Telegram
     * @return accusé de réception avec le taskId court
     */
    @Chat
    public String chat(TelegramUpdateContext ctx) {
        String text = ctx.getText();
        if (text == null || text.isBlank()) {
            return "Je n'ai pas reçu de texte.";
        }

        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            log.warn("Telegram chat — DISPATCHER ABSENT. Vérifie que CortexFactory, AgentLoop et ChatClient sont bien câblés."
                    + " TelegramHI={}", telegramProvider.getIfAvailable() != null ? "présent" : "absent");
            return "⚠️ Le moteur agent n'est pas démarré (aucune AgentFactory configurée).";
        }

        String taskId = UUID.randomUUID().toString();
        String conversationId = ctx.getChatId() != null
                ? String.valueOf(ctx.getChatId())
                : "telegram";

        TaskMessage message = new TaskMessage(
                taskId,
                TaskType.USER_REQUEST,
                "cortex",
                text,
                AgentContext.of("default", "none", conversationId, taskId));

        taskQueue.submit(message);
        log.info("Telegram chat — tâche soumise taskId={}, texte='{}'",
                taskId, truncate(text, 60));

        return String.format("⏳ Message reçu (tâche %s).\nJe traite, tu recevras la réponse dans un instant…",
                taskId);
    }

    // ── Commandes ────────────────────────────────────────────────────────

    /**
     * Commande {@code /stop <taskId>} — arrête une tâche en cours.
     *
     * @param ctx contexte de l'update Telegram
     * @return message de confirmation ou d'erreur
     */
    @Command(value = "/stop", description = "Arrêter une tâche : /stop <taskId>")
    public String stop(TelegramUpdateContext ctx) {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            return "Dispatcher non disponible.";
        }

        if (ctx.getArgs() == null || ctx.getArgs().isEmpty()) {
            return "Usage : /stop <taskId>";
        }

        String taskId = ctx.getArgs().get(0);
        boolean stopped = dispatcher.stop(taskId);
        log.info("Telegram /stop — taskId={}, stopped={}", taskId, stopped);

        return stopped
                ? String.format("✅ Tâche %s arrêtée.", truncate(taskId, 12))
                : String.format("❓ Tâche %s introuvable.", truncate(taskId, 12));
    }

    /**
     * Commande {@code /status} — liste les tâches actives.
     *
     * @param ctx contexte de l'update Telegram
     * @return liste formatée des tâches actives
     */
    @Command(value = "/status", description = "Lister les tâches actives")
    public String status(TelegramUpdateContext ctx) {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            return "Dispatcher non disponible.";
        }

        Set<String> active = dispatcher.listActiveTaskIds();
        log.info("Telegram /status — {} tâche(s) active(s)", active.size());

        if (active.isEmpty()) {
            return "Aucune tâche active.";
        }

        StringBuilder sb = new StringBuilder("Tâches actives :\n");
        for (String taskId : active) {
            sb.append("  • ").append(taskId).append("\n");
        }
        return sb.toString();
    }

    // ── Callbacks HITL (valeurs fixes, un handler par décision) ──────────

    /**
     * Callback HITL — Une fois.
     *
     * @param ctx contexte de l'update Telegram
     * @return message de confirmation
     */
    @CallbackQuery(TelegramHumanInteraction.CB_ALLOW_ONCE)
    public String onAllowOnce(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_ONCE);
    }

    /**
     * Callback HITL — Session.
     *
     * @param ctx contexte de l'update Telegram
     * @return message de confirmation
     */
    @CallbackQuery(TelegramHumanInteraction.CB_ALLOW_SESSION)
    public String onAllowSession(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_SESSION);
    }

    /**
     * Callback HITL — Projet.
     *
     * @param ctx contexte de l'update Telegram
     * @return message de confirmation
     */
    @CallbackQuery(TelegramHumanInteraction.CB_ALLOW_PROJECT)
    public String onAllowProject(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_PROJECT);
    }

    /**
     * Callback HITL — Toujours.
     *
     * @param ctx contexte de l'update Telegram
     * @return message de confirmation
     */
    @CallbackQuery(TelegramHumanInteraction.CB_ALLOW_ALWAYS)
    public String onAllowAlways(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_ALWAYS);
    }

    /**
     * Callback HITL — Refuser.
     *
     * @param ctx contexte de l'update Telegram
     * @return message de confirmation
     */
    @CallbackQuery(TelegramHumanInteraction.CB_DENY)
    public String onDeny(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.DENY);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Résout la demande HITL en attente avec la décision donnée.
     *
     * @param decision décision à appliquer
     * @return message de confirmation pour l'utilisateur Telegram
     */
    private String resolveHitl(ConsentDecision decision) {
        TelegramHumanInteraction telegram = telegramProvider.getIfAvailable();
        if (telegram == null) {
            return "Canal Telegram non configuré.";
        }
        boolean resolved = telegram.resolveAsk(decision);
        log.info("Telegram HITL callback — decision={}, resolved={}", decision, resolved);
        return resolved
                ? String.format("✅ Décision enregistrée : %s", decision)
                : "⏱ Demande expirée ou déjà traitée.";
    }

    /**
     * Tronque un texte pour l'affichage.
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "?";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
