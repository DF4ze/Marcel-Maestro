package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.telegrambots.model.TelegramButtonView;
import fr.ses10doigts.telegrambots.model.TelegramUpdateContext;
import fr.ses10doigts.telegrambots.model.TelegramView;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.CallbackQuery;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Chat;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Command;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.TelegramController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Contrôleur Telegram pour le pilotage de Marcel Maestro (E2-M5).
 *
 * <p>Gère :</p>
 * <ul>
 *   <li>{@code /stop} — arrête une tâche en cours.</li>
 *   <li>{@code /status} — liste les tâches actives.</li>
 *   <li>{@code /projects} — liste les projets ACTIVE avec leur nombre de conversations ouvertes (E2-M5).</li>
 *   <li>{@code /switch <name>} — change le projet actif de la session Telegram (E2-M5).</li>
 *   <li>Messages libres — soumis au cortex via la {@link TaskQueue}.</li>
 *   <li>Callbacks HITL (11 handlers).</li>
 * </ul>
 *
 * <p><strong>E2-M5 — Session Telegram :</strong><br>
 * Le projet actif est maintenu par {@link TelegramSessionService} (in-memory,
 * {@link java.util.concurrent.ConcurrentHashMap ConcurrentHashMap} chatId → projectId).
 * En l'absence de session active pour un chatId, la stratégie de repli est :
 * premier projet ACTIVE disponible. Si aucun projet n'existe, un message explicite
 * est retourné.</p>
 *
 * <p><strong>E2-M5 — Préfixe [NomProjet] :</strong><br>
 * Appliqué par {@link TelegramHumanInteraction#notify} — non géré ici.</p>
 */
@TelegramController
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
@Slf4j
public class TelegramMmController {

    private static final String CB_SWITCH_PREFIX = "switch_project_";
    private static final int MAX_SWITCH_SUGGESTIONS = 6;

    private final ObjectProvider<Dispatcher> dispatcherProvider;
    private final ObjectProvider<TelegramHumanInteraction> telegramProvider;
    private final ObjectProvider<ConversationService> conversationServiceProvider;
    private final TaskQueue taskQueue;
    private final TelegramSessionService sessionService;

    /**
     * Construit le contrôleur Telegram MM.
     *
     * @param dispatcherProvider          provider du Dispatcher (optionnel)
     * @param telegramProvider            provider du TelegramHumanInteraction (optionnel)
     * @param conversationServiceProvider provider du ConversationService (optionnel)
     * @param taskQueue                   file de tâches
     * @param sessionService              gestionnaire de sessions Telegram (E2-M5)
     */
    public TelegramMmController(
            ObjectProvider<Dispatcher> dispatcherProvider,
            ObjectProvider<TelegramHumanInteraction> telegramProvider,
            ObjectProvider<ConversationService> conversationServiceProvider,
            TaskQueue taskQueue,
            TelegramSessionService sessionService) {
        this.dispatcherProvider = dispatcherProvider;
        this.telegramProvider = telegramProvider;
        this.conversationServiceProvider = conversationServiceProvider;
        this.taskQueue = taskQueue;
        this.sessionService = sessionService;
    }

    // ── Chat ────────────────────────────────────────────────────────────

    /**
     * Reçoit un message libre de l'utilisateur, le soumet au cortex via la
     * {@link TaskQueue} et retourne un accusé de réception immédiat.
     *
     * <p>E2-M5 : le projectId est résolu depuis la session active du chatId
     * (via {@link TelegramSessionService#resolveProjectId}). Si aucun projet
     * n'est disponible, un message d'erreur est retourné.</p>
     *
     * <p>E3-M0 : la conversation active du chatId est réutilisée tant qu'elle
     * n'est pas réinitialisée via {@code /reset}.</p>
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

        // ── Résolution projectId via session active (E2-M5) ────────────────
        Long chatId = ctx.getChatId();
        Optional<String> resolvedProjectId = sessionService.resolveProjectId(chatId);

        if (resolvedProjectId.isEmpty()) {
            log.warn("Telegram chat — chatId={} : aucun projet ACTIVE disponible", chatId);
            return "⚠️ Aucun projet actif disponible. Crée un projet via l'API REST puis réessaie.";
        }

        String projectId = resolvedProjectId.get();
        String conversationId = sessionService.getActiveConversationId(chatId).orElse(null);

        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService != null) {
            if (conversationId == null) {
                try {
                    ConversationEntity conv = conversationService.startConversation(projectId);
                    conversationId = conv.getId();
                    sessionService.setActiveConversationId(chatId, conversationId);
                    log.debug("Telegram chat — nouvelle conversationId injectée={}, projectId={}",
                            conversationId, projectId);
                } catch (Exception e) {
                    log.warn("Telegram chat — impossible de créer la conversation pour projectId={} : {}. Fallback sur chatId.",
                            projectId, e.getMessage());
                    conversationId = chatId != null ? String.valueOf(chatId) : "telegram";
                }
            } else {
                log.info("Telegram chat — réutilisation conversation active chatId={}, conversationId={}",
                        chatId, conversationId);
            }
        } else {
            log.warn("Telegram chat — ConversationService absent. Fallback sur chatId comme conversationId.");
            conversationId = chatId != null ? String.valueOf(chatId) : "telegram";
        }

        // ── Soumission TaskMessage ──────────────────────────────────────────
        String taskId = UUID.randomUUID().toString();
        TaskMessage message = new TaskMessage(
                taskId,
                TaskType.USER_REQUEST,
                "cortex",
                text,
                AgentContext.of("default", projectId, conversationId, taskId));

        taskQueue.submit(message);
        log.info("Telegram chat — tâche soumise taskId={}, projectId={}, conversationId={}, texte='{}'",
                taskId, projectId, conversationId, truncate(text, 60));

        return String.format("⏳ Message reçu (tâche %s).\nJe traite, tu recevras la réponse dans un instant…",
                truncate(taskId, 12));
    }

    /**
     * Commande {@code /reset} — oublie la conversation active du chat courant.
     *
     * @param ctx contexte de l'update Telegram
     * @return confirmation de réinitialisation
     */
    @Command(value = "/reset", description = "Réinitialiser la conversation active")
    public String reset(TelegramUpdateContext ctx) {
        Long chatId = ctx.getChatId();
        sessionService.clearActiveConversationId(chatId);
        log.info("Telegram /reset — chatId={}", chatId);
        return "✅ Conversation réinitialisée. Le prochain message démarrera une nouvelle conversation.";
    }

    // ── Commandes ────────────────────────────────────────────────────────────

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

    /**
     * Commande {@code /projects} — liste les projets ACTIVE avec le nombre de conversations ouvertes.
     *
     * <p>Format de sortie Telegram Markdown :</p>
     * <pre>
     * 📂 *Projets actifs*
     * • *Mon Projet* — 3 conversations ouvertes
     * • *Autre Projet* — 1 conversation ouverte
     * </pre>
     *
     * @param ctx contexte de l'update Telegram
     * @return liste Markdown des projets actifs
     */
    @Command(value = "/projects", description = "Lister les projets actifs")
    public String projects(TelegramUpdateContext ctx) {
        log.info("Telegram /projects — chatId={}", ctx.getChatId());

        List<ProjectEntity> activeProjects = sessionService.listActiveProjects();

        if (activeProjects.isEmpty()) {
            return "Aucun projet actif. Crée un projet via l'API REST.";
        }

        // Batch : une seule requête SQL pour tous les projets (anti N+1)
        List<String> projectIds = activeProjects.stream().map(ProjectEntity::getId).toList();
        Map<String, Long> openCountByProject = sessionService.countOpenConversationsByProjects(projectIds);

        StringBuilder sb = new StringBuilder("📂 *Projets actifs*\n");
        for (ProjectEntity project : activeProjects) {
            long openConvCount = openCountByProject.getOrDefault(project.getId(), 0L);
            String convLabel = openConvCount == 1 ? "1 conversation ouverte" :
                    openConvCount + " conversations ouvertes";
            sb.append("• *").append(project.getName()).append("* — ").append(convLabel).append("\n");
        }

        // Indiquer le projet actif de la session
        Optional<String> activeProjectId = sessionService.getActiveProjectId(ctx.getChatId());
        if (activeProjectId.isPresent()) {
            activeProjects.stream()
                    .filter(p -> p.getId().equals(activeProjectId.get()))
                    .findFirst()
                    .ifPresent(p -> sb.append("\n_Projet actif : ").append(p.getName()).append("_"));
        }

        return sb.toString();
    }

    /**
     * Commande {@code /switch <name>} — change le projet actif de la session Telegram.
     *
     * <p>La recherche est insensible à la casse sur le nom ou le slug du projet.
     * Le projet doit être ACTIVE. Log.info enregistre le switch (coding rules).</p>
     *
     * @param ctx contexte de l'update Telegram
     * @return confirmation du switch ou message d'erreur
     */
    @Command(value = "/switch", description = "Changer de projet actif : /switch <nom>")
    public Object switchProject(TelegramUpdateContext ctx) {
        Long chatId = ctx.getChatId();
        String nameArg = ctx.getArgs() == null || ctx.getArgs().isEmpty()
                ? ""
                : String.join(" ", ctx.getArgs()).trim();

        if (!nameArg.isBlank()) {
            Optional<ProjectEntity> exact = sessionService.findActiveProjectByName(nameArg);
            if (exact.isPresent()) {
                return activateProject(chatId, exact.get());
            }
        }

        List<ProjectEntity> candidates = sessionService.findActiveProjectsByQuery(nameArg, MAX_SWITCH_SUGGESTIONS);
        if (candidates.isEmpty()) {
            log.info("Telegram /switch — chatId={}, aucune suggestion pour '{}'", chatId, nameArg);
            return nameArg.isBlank()
                    ? "❓ Aucun projet actif disponible."
                    : String.format("❓ Aucun projet actif ne ressemble à '%s'.\nUtilise /projects pour voir les projets disponibles.", nameArg);
        }
        if (candidates.size() == 1) {
            log.info("Telegram /switch — chatId={}, match approximatif unique pour '{}': {}",
                    chatId, nameArg, candidates.getFirst().getName());
            return activateProject(chatId, candidates.getFirst());
        }

        sessionService.setSwitchSuggestions(chatId, candidates.stream().map(ProjectEntity::getId).toList());
        return buildSwitchSuggestionView(nameArg, candidates);
    }

    // ── Callbacks HITL ───────────────────────────────────────────────────────
    // 11 handlers : 1 (une fois) + 9 (3 scopes × 3 persistances) + 1 (refus)

    @CallbackQuery(TelegramHumanInteraction.CB_ALLOW_ONCE)
    public String onAllowOnce(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_ONCE);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_DENY)
    public String onDeny(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.DENY);
    }

    // ── Stricte ──────────────────────────────────────────────────────────────

    @CallbackQuery(TelegramHumanInteraction.CB_STRICT_SESSION)
    public String onStrictSession(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_STRICT_SESSION);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_STRICT_PROJECT)
    public String onStrictProject(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_STRICT_PROJECT);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_STRICT_ALWAYS)
    public String onStrictAlways(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_STRICT_ALWAYS);
    }

    // ── Local ─────────────────────────────────────────────────────────────────

    @CallbackQuery(TelegramHumanInteraction.CB_LOCAL_SESSION)
    public String onLocalSession(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_LOCAL_SESSION);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_LOCAL_PROJECT)
    public String onLocalProject(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_LOCAL_PROJECT);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_LOCAL_ALWAYS)
    public String onLocalAlways(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_LOCAL_ALWAYS);
    }

    // ── Large ─────────────────────────────────────────────────────────────────

    @CallbackQuery(TelegramHumanInteraction.CB_LARGE_SESSION)
    public String onLargeSession(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_LARGE_SESSION);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_LARGE_PROJECT)
    public String onLargeProject(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_LARGE_PROJECT);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_LARGE_ALWAYS)
    public String onLargeAlways(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_LARGE_ALWAYS);
    }

    @CallbackQuery(CB_SWITCH_PREFIX + "0")
    public String onSwitch0(TelegramUpdateContext ctx) {
        return resolveSwitchSuggestion(ctx, 0);
    }

    @CallbackQuery(CB_SWITCH_PREFIX + "1")
    public String onSwitch1(TelegramUpdateContext ctx) {
        return resolveSwitchSuggestion(ctx, 1);
    }

    @CallbackQuery(CB_SWITCH_PREFIX + "2")
    public String onSwitch2(TelegramUpdateContext ctx) {
        return resolveSwitchSuggestion(ctx, 2);
    }

    @CallbackQuery(CB_SWITCH_PREFIX + "3")
    public String onSwitch3(TelegramUpdateContext ctx) {
        return resolveSwitchSuggestion(ctx, 3);
    }

    @CallbackQuery(CB_SWITCH_PREFIX + "4")
    public String onSwitch4(TelegramUpdateContext ctx) {
        return resolveSwitchSuggestion(ctx, 4);
    }

    @CallbackQuery(CB_SWITCH_PREFIX + "5")
    public String onSwitch5(TelegramUpdateContext ctx) {
        return resolveSwitchSuggestion(ctx, 5);
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

    private String resolveSwitchSuggestion(TelegramUpdateContext ctx, int index) {
        Long chatId = ctx.getChatId();
        Optional<ProjectEntity> project = sessionService.resolveSwitchSuggestion(chatId, index);
        if (project.isEmpty()) {
            return "❓ Cette suggestion n'est plus disponible. Relance /switch.";
        }
        return activateProject(chatId, project.get());
    }

    private String activateProject(Long chatId, ProjectEntity project) {
        sessionService.setActiveProject(chatId, project.getId(), project.getName());

        long openConvCount = sessionService.countOpenConversations(project.getId());
        String convLabel = openConvCount == 1 ? "1 conversation ouverte" :
                openConvCount + " conversations ouvertes";

        return String.format("✅ Projet actif : *%s*\n%s", project.getName(), convLabel);
    }

    private TelegramView buildSwitchSuggestionView(String query, List<ProjectEntity> candidates) {
        String text = query == null || query.isBlank()
                ? "Sélectionne le projet actif :"
                : String.format("Plusieurs projets ressemblent à '%s'. Sélectionne le bon :", query);

        List<List<TelegramButtonView>> buttons = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ProjectEntity project = candidates.get(i);
            buttons.add(List.of(new TelegramButtonView(project.getName(), CB_SWITCH_PREFIX + i)));
        }

        return TelegramView.builder()
                .text(text)
                .buttons(buttons)
                .build();
    }

    /**
     * Tronque un texte pour l'affichage.
     *
     * @param text   texte à tronquer
     * @param maxLen longueur maximale
     * @return texte tronqué avec "…" si nécessaire
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "?";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
