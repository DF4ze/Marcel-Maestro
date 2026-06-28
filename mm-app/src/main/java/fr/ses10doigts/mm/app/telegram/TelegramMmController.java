package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.app.conversation.ArchivedConversationReadOnlyException;
import fr.ses10doigts.mm.app.conversation.ConversationBriefService;
import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.telegrambots.model.TelegramButtonView;
import fr.ses10doigts.telegrambots.model.TelegramUpdateContext;
import fr.ses10doigts.telegrambots.model.TelegramView;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.CallbackQuery;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Chat;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Command;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.TelegramController;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Controleur Telegram pour le pilotage de Marcel Maestro.
 */
@TelegramController
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
@Slf4j
public class TelegramMmController {

    private static final String CB_SWITCH_PREFIX = "switch_project_";
    private static final String CB_CONVERSATIONS_PROJECT_PREFIX = "conversations_project_";
    private static final String CB_NAV_PROJECT_PREFIX = "nav_project_";
    private static final String CB_NAV_CONVERSATION_PREFIX = "nav_conv_";
    private static final String CB_NAV_PROJECT_SWITCH = "nav_project_switch";
    private static final String CB_NAV_PROJECT_ARCHIVE = "nav_project_archive";
    private static final String CB_NAV_PROJECT_DELETE = "nav_project_delete";
    private static final String CB_NAV_CONVERSATION_SWITCH = "nav_conv_switch";
    private static final String CB_NAV_CONVERSATION_ARCHIVE = "nav_conv_archive";
    private static final String CB_NAV_CONVERSATION_DELETE = "nav_conv_delete";
    private static final String CB_NAV_BACK_TO_PROJECTS = "nav_back_projects";
    private static final String CB_NAV_BACK_TO_PROJECT = "nav_back_project";
    private static final String CB_DELETE_PROJECT_PREFIX = "delete_project_";
    private static final String CB_DELETE_CONVERSATION_PREFIX = "delete_conv_";
    private static final String CB_ARCHIVE_PROJECT_PREFIX = "archive_project_";
    private static final String CB_ARCHIVE_CONVERSATION_PREFIX = "archive_conv_";
    private static final String CB_CONFIRM_DELETE = "confirm_delete";
    private static final String CB_CANCEL_MUTATION = "cancel_mutation";
    private static final int MAX_NAV_SUGGESTIONS = 20;
    private static final int MAX_SWITCH_SUGGESTIONS = 6;
    private static final int MAX_CONVERSATION_SUGGESTIONS = 5;
    private static final int MAX_MUTATION_PROJECT_SUGGESTIONS = 6;
    private static final int MAX_MUTATION_CONVERSATION_SUGGESTIONS = 6;

    private final ObjectProvider<Dispatcher> dispatcherProvider;
    private final ObjectProvider<TelegramHumanInteraction> telegramProvider;
    private final ObjectProvider<ConversationService> conversationServiceProvider;
    private final ObjectProvider<ConversationBriefService> conversationBriefServiceProvider;
    private final ObjectProvider<ProjectService> projectServiceProvider;
    private final TelegramSessionService sessionService;

    /**
     * Construit le controleur Telegram MM.
     *
     * @param dispatcherProvider provider du Dispatcher
     * @param telegramProvider provider du TelegramHumanInteraction
     * @param conversationServiceProvider provider du ConversationService
     * @param conversationBriefServiceProvider provider du ConversationBriefService
     * @param projectServiceProvider provider du ProjectService
     * @param sessionService gestionnaire de sessions Telegram
     */
    public TelegramMmController(
            ObjectProvider<Dispatcher> dispatcherProvider,
            ObjectProvider<TelegramHumanInteraction> telegramProvider,
            ObjectProvider<ConversationService> conversationServiceProvider,
            ObjectProvider<ConversationBriefService> conversationBriefServiceProvider,
            ObjectProvider<ProjectService> projectServiceProvider,
            TelegramSessionService sessionService) {
        this.dispatcherProvider = dispatcherProvider;
        this.telegramProvider = telegramProvider;
        this.conversationServiceProvider = conversationServiceProvider;
        this.conversationBriefServiceProvider = conversationBriefServiceProvider;
        this.projectServiceProvider = projectServiceProvider;
        this.sessionService = sessionService;
    }

    /**
     * Recoit un message libre et le traite via le flux conversationnel.
     *
     * <p>Tout texte commencant par {@code /} est traite comme commande systeme
     * ou commande inconnue. Il ne doit jamais etre envoye au LLM ni a la file
     * de taches pour eviter des HITL parasites.</p>
     *
     * <p>Si la conversation active est archivee, le message est refuse et
     * l'utilisateur doit explicitement choisir une conversation OPEN ou en creer
     * une nouvelle via {@code /new}.</p>
     *
     * @param ctx contexte de l'update Telegram
     * @return reponse conversationnelle, message d'erreur ou aide commande
     */
    @Chat
    public String chat(TelegramUpdateContext ctx) {
        String text = ctx.getText();
        if (text == null || text.isBlank()) {
            return "Je n'ai pas recu de texte.";
        }
        if (isSystemCommand(text)) {
            log.info("Telegram chat - commande non resolue interceptee, chatId={}, text='{}'",
                    ctx.getChatId(), truncate(text, 40));
            return "Commande inconnue. Utilise /projects, /switch, /conversations, /conv <n>, /new, /brief, /reset, /delete, /archive, /status ou /stop.";
        }

        Optional<String> pendingArchiveResponse = consumePendingArchiveReason(ctx, text);
        if (pendingArchiveResponse.isPresent()) {
            return pendingArchiveResponse.get();
        }

        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService == null) {
            log.warn("Telegram chat - ConversationService absent. TelegramHI={}",
                    telegramProvider.getIfAvailable() != null ? "present" : "absent");
            return "ConversationService non disponible.";
        }

        Long chatId = ctx.getChatId();
        Optional<String> resolvedProjectId = sessionService.resolveProjectId(chatId);
        if (resolvedProjectId.isEmpty()) {
            log.warn("Telegram chat - chatId={} : aucun projet ACTIVE disponible", chatId);
            return "Aucun projet actif disponible. Cree un projet via l'API REST puis reessaie.";
        }

        String projectId = resolvedProjectId.get();
        String conversationId = resolveTelegramConversationId(chatId, projectId, conversationService);
        if (conversationId == null) {
            return "La conversation active est archivee. Utilise /new pour en demarrer une nouvelle ou /conversations pour reprendre une conversation ouverte.";
        }

        String response = conversationService.chat(conversationId, text);
        log.info("Telegram chat - reponse LLM generee, projectId={}, conversationId={}, texte='{}'",
                projectId, conversationId, truncate(text, 60));
        return response;
    }

    /**
     * Reinitialise la conversation active du chat courant.
     *
     * @param ctx contexte Telegram
     * @return message de confirmation
     */
    @Command(value = "/reset", description = "Reinitialiser la conversation active")
    public String reset(TelegramUpdateContext ctx) {
        Long chatId = ctx.getChatId();
        sessionService.clearActiveConversationId(chatId);
        sessionService.clearTransientState(chatId);
        log.info("Telegram /reset - chatId={}", chatId);
        return "Conversation reinitialisee. Le prochain message demarrera une nouvelle conversation.";
    }

    @Command(value = "/cancel", description = "Annuler une action Telegram en attente")
    public String cancel(TelegramUpdateContext ctx) {
        sessionService.clearTransientState(ctx.getChatId());
        return "Action annulee.";
    }

    /**
     * Cree explicitement une nouvelle conversation OPEN pour le projet actif.
     *
     * @param ctx contexte Telegram
     * @return message de confirmation
     */
    @Command(value = "/new", description = "Demarrer une nouvelle conversation")
    public String newConversation(TelegramUpdateContext ctx) {
        Long chatId = ctx.getChatId();
        Optional<String> resolvedProjectId = sessionService.resolveProjectId(chatId);
        if (resolvedProjectId.isEmpty()) {
            return "Aucun projet actif. Utilise /switch pour en selectionner un.";
        }

        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService == null) {
            return "ConversationService non disponible.";
        }

        ConversationEntity conversation = conversationService.startConversation(resolvedProjectId.get());
        sessionService.setActiveConversationId(chatId, conversation.getId());
        sessionService.clearConversationSuggestions(chatId);
        log.info("Telegram /new - chatId={}, projectId={}, conversationId={}",
                chatId, resolvedProjectId.get(), conversation.getId());
        return "Nouvelle conversation demarree.\nEnvoie ton premier message a Marcel.";
    }

    /**
     * Liste les conversations OPEN du projet actif et memorise les suggestions.
     *
     * @param ctx contexte Telegram
     * @return liste formatee des conversations
     */
    @Command(value = "/conversations", description = "Lister les conversations ouvertes")
    public Object conversations(TelegramUpdateContext ctx) {
        Long chatId = ctx.getChatId();
        List<ProjectEntity> activeProjects = sessionService.listActiveProjectsByRecentActivity().stream()
                .limit(MAX_NAV_SUGGESTIONS)
                .toList();
        if (activeProjects.isEmpty()) {
            return "Aucun projet actif. Cree un projet via l'API REST.";
        }
        sessionService.openNavigation(
                chatId,
                TelegramSessionService.NavigationIntent.BROWSE_CONVERSATIONS,
                activeProjects.stream().map(ProjectEntity::getId).toList());
        return buildNavigationProjectSelectionView(
                chatId,
                "Selectionne le projet pour voir ses conversations ouvertes :",
                activeProjects);
    }

    /**
     * Reprend une conversation OPEN a partir d'une suggestion numerotee.
     *
     * @param ctx contexte Telegram
     * @return message de confirmation ou d'erreur
     */
    @Command(value = "/conv", description = "Reprendre une conversation : /conv <n>")
    public String conversation(TelegramUpdateContext ctx) {
        Long chatId = ctx.getChatId();
        if (ctx.getArgs() == null || ctx.getArgs().isEmpty()) {
            return "Usage : /conv <n>";
        }

        int index;
        try {
            index = Integer.parseInt(ctx.getArgs().getFirst()) - 1;
        } catch (NumberFormatException ex) {
            log.info("Telegram /conv - index invalide, chatId={}, rawArg='{}'",
                    chatId, ctx.getArgs().getFirst());
            return "Index invalide. Utilise /conversations puis /conv <n>.";
        }

        Optional<ConversationEntity> resolved = sessionService.resolveConversationSuggestion(chatId, index);
        if (resolved.isEmpty()) {
            return "Suggestion introuvable ou conversation deja archivee. Relance /conversations.";
        }

        ConversationEntity conversation = resolved.get();
        sessionService.setActiveConversationId(chatId, conversation.getId());
        log.info("Telegram /conv - chatId={}, conversationId={}", chatId, conversation.getId());
        return String.format("Conversation reprise : \"%s\"", resolveConversationDisplayTitle(conversation));
    }

    /**
     * Arrete une tache en cours.
     *
     * @param ctx contexte Telegram
     * @return message de confirmation ou d'erreur
     */
    @Command(value = "/stop", description = "Arreter une tache : /stop <taskId>")
    public String stop(TelegramUpdateContext ctx) {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            return "Dispatcher non disponible.";
        }
        if (ctx.getArgs() == null || ctx.getArgs().isEmpty()) {
            return "Usage : /stop <taskId>";
        }

        String taskId = ctx.getArgs().getFirst();
        boolean stopped = dispatcher.stop(taskId);
        log.info("Telegram /stop - taskId={}, stopped={}", taskId, stopped);
        return stopped
                ? String.format("Tache %s arretee.", truncate(taskId, 12))
                : String.format("Tache %s introuvable.", truncate(taskId, 12));
    }

    /**
     * Liste les taches actives.
     *
     * @param ctx contexte Telegram
     * @return liste formatee des taches actives
     */
    @Command(value = "/status", description = "Lister les taches actives")
    public String status(TelegramUpdateContext ctx) {
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            return "Dispatcher non disponible.";
        }

        Set<String> active = dispatcher.listActiveTaskIds();
        log.info("Telegram /status - {} tache(s) active(s)", active.size());
        if (active.isEmpty()) {
            return "Aucune tache active.";
        }

        StringBuilder sb = new StringBuilder("Taches actives :\n");
        for (String taskId : active) {
            sb.append("  - ").append(taskId).append("\n");
        }
        return sb.toString();
    }

    /**
     * Produit un brief de la conversation active de la session Telegram.
     *
     * @param ctx contexte Telegram
     * @return brief courant ou message explicite si aucune conversation active
     */
    @Command(value = "/brief", description = "Resumer la conversation active")
    public String brief(TelegramUpdateContext ctx) {
        ConversationBriefService briefService = conversationBriefServiceProvider.getIfAvailable();
        if (briefService == null) {
            return "ConversationBriefService non disponible.";
        }

        Long chatId = ctx.getChatId();
        Optional<String> conversationId = sessionService.getActiveConversationId(chatId);
        if (conversationId.isEmpty()) {
            return "Aucune conversation active. Envoie d'abord un premier message pour creer la conversation.";
        }

        String brief = briefService.brief(conversationId.get());
        log.info("Telegram /brief - chatId={}, conversationId={}", chatId, conversationId.get());
        return "Brief courant :\n" + brief;
    }

    /**
     * Liste les projets ACTIVE avec leur nombre de conversations ouvertes.
     *
     * @param ctx contexte Telegram
     * @return liste Markdown des projets actifs
     */
    @Command(value = "/projects", description = "Lister les projets actifs")
    public Object projects(TelegramUpdateContext ctx) {
        log.info("Telegram /projects - chatId={}", ctx.getChatId());

        List<ProjectEntity> activeProjects = sessionService.listActiveProjectsByRecentActivity().stream()
                .limit(MAX_NAV_SUGGESTIONS)
                .toList();
        if (activeProjects.isEmpty()) {
            return "Aucun projet actif. Cree un projet via l'API REST.";
        }
        sessionService.openNavigation(
                ctx.getChatId(),
                TelegramSessionService.NavigationIntent.BROWSE_PROJECTS,
                activeProjects.stream().map(ProjectEntity::getId).toList());
        return buildNavigationProjectSelectionView(ctx.getChatId(), "Projets actifs :", activeProjects);
    }

    /**
     * Change le projet actif de la session Telegram.
     *
     * @param ctx contexte Telegram
     * @return confirmation de switch ou vue de suggestions
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

        List<ProjectEntity> candidates = nameArg.isBlank()
                ? sessionService.listActiveProjectsByRecentActivity().stream()
                        .limit(MAX_NAV_SUGGESTIONS)
                        .toList()
                : sessionService.findActiveProjectsByQuery(nameArg, MAX_NAV_SUGGESTIONS);
        if (candidates.isEmpty()) {
            log.info("Telegram /switch - chatId={}, aucune suggestion pour '{}'", chatId, nameArg);
            return nameArg.isBlank()
                    ? "Aucun projet actif disponible."
                    : String.format("Aucun projet actif ne ressemble a '%s'.\nUtilise /projects pour voir les projets disponibles.", nameArg);
        }
        if (candidates.size() == 1) {
            log.info("Telegram /switch - chatId={}, match approximatif unique pour '{}': {}",
                    chatId, nameArg, candidates.getFirst().getName());
            return activateProject(chatId, candidates.getFirst());
        }

        sessionService.openNavigation(
                chatId,
                TelegramSessionService.NavigationIntent.SWITCH,
                candidates.stream().map(ProjectEntity::getId).toList());
        return buildNavigationProjectSelectionView(
                chatId,
                nameArg.isBlank()
                        ? "Selectionne le projet a activer :"
                        : String.format("Selectionne le projet correspondant a '%s' :", nameArg),
                candidates);
    }

    @Command(value = "/delete", description = "Supprimer un projet ou une conversation : /delete project|conv")
    public Object delete(TelegramUpdateContext ctx) {
        String scope = firstArg(ctx);
        if (scope == null) {
            return "Usage : /delete project|conv";
        }
        String normalized = normalizeScope(scope);
        if (!"project".equals(normalized) && !"conv".equals(normalized)) {
            return "Usage : /delete project|conv";
        }
        List<ProjectEntity> activeProjects = sessionService.listActiveProjectsByRecentActivity().stream()
                .limit(MAX_NAV_SUGGESTIONS)
                .toList();
        if (activeProjects.isEmpty()) {
            return "Aucun projet actif.";
        }
        sessionService.openNavigation(
                ctx.getChatId(),
                TelegramSessionService.NavigationIntent.DELETE,
                activeProjects.stream().map(ProjectEntity::getId).toList());
        return buildNavigationProjectSelectionView(
                ctx.getChatId(),
                "Selectionne le projet, puis la conversation a supprimer si besoin :",
                activeProjects);
    }

    @Command(value = "/archive", description = "Archiver un projet ou une conversation : /archive project|conv")
    public Object archive(TelegramUpdateContext ctx) {
        String scope = firstArg(ctx);
        if (scope == null) {
            return "Usage : /archive project|conv";
        }
        String normalized = normalizeScope(scope);
        if (!"project".equals(normalized) && !"conv".equals(normalized)) {
            return "Usage : /archive project|conv";
        }
        List<ProjectEntity> activeProjects = sessionService.listActiveProjectsByRecentActivity().stream()
                .limit(MAX_NAV_SUGGESTIONS)
                .toList();
        if (activeProjects.isEmpty()) {
            return "Aucun projet actif.";
        }
        sessionService.openNavigation(
                ctx.getChatId(),
                TelegramSessionService.NavigationIntent.ARCHIVE,
                activeProjects.stream().map(ProjectEntity::getId).toList());
        return buildNavigationProjectSelectionView(
                ctx.getChatId(),
                "Selectionne le projet, puis la conversation a archiver si besoin :",
                activeProjects);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_ALLOW_ONCE)
    public String onAllowOnce(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.ALLOW_ONCE);
    }

    @CallbackQuery(TelegramHumanInteraction.CB_DENY)
    public String onDeny(TelegramUpdateContext ctx) {
        return resolveHitl(ConsentDecision.DENY);
    }

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

    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "0")
    public Object onNavProject0(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 0); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "1")
    public Object onNavProject1(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 1); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "2")
    public Object onNavProject2(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 2); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "3")
    public Object onNavProject3(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 3); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "4")
    public Object onNavProject4(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 4); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "5")
    public Object onNavProject5(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 5); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "6")
    public Object onNavProject6(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 6); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "7")
    public Object onNavProject7(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 7); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "8")
    public Object onNavProject8(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 8); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "9")
    public Object onNavProject9(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 9); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "10")
    public Object onNavProject10(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 10); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "11")
    public Object onNavProject11(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 11); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "12")
    public Object onNavProject12(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 12); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "13")
    public Object onNavProject13(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 13); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "14")
    public Object onNavProject14(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 14); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "15")
    public Object onNavProject15(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 15); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "16")
    public Object onNavProject16(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 16); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "17")
    public Object onNavProject17(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 17); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "18")
    public Object onNavProject18(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 18); }
    @CallbackQuery(CB_NAV_PROJECT_PREFIX + "19")
    public Object onNavProject19(TelegramUpdateContext ctx) { return onNavigationProjectSelected(ctx, 19); }

    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "0")
    public Object onNavConversation0(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 0); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "1")
    public Object onNavConversation1(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 1); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "2")
    public Object onNavConversation2(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 2); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "3")
    public Object onNavConversation3(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 3); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "4")
    public Object onNavConversation4(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 4); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "5")
    public Object onNavConversation5(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 5); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "6")
    public Object onNavConversation6(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 6); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "7")
    public Object onNavConversation7(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 7); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "8")
    public Object onNavConversation8(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 8); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "9")
    public Object onNavConversation9(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 9); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "10")
    public Object onNavConversation10(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 10); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "11")
    public Object onNavConversation11(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 11); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "12")
    public Object onNavConversation12(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 12); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "13")
    public Object onNavConversation13(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 13); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "14")
    public Object onNavConversation14(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 14); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "15")
    public Object onNavConversation15(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 15); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "16")
    public Object onNavConversation16(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 16); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "17")
    public Object onNavConversation17(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 17); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "18")
    public Object onNavConversation18(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 18); }
    @CallbackQuery(CB_NAV_CONVERSATION_PREFIX + "19")
    public Object onNavConversation19(TelegramUpdateContext ctx) { return onNavigationConversationSelected(ctx, 19); }

    @CallbackQuery(CB_NAV_PROJECT_SWITCH)
    public String onNavProjectSwitch(TelegramUpdateContext ctx) { return executeSelectedProjectSwitch(ctx.getChatId()); }

    @CallbackQuery(CB_NAV_PROJECT_ARCHIVE)
    public Object onNavProjectArchive(TelegramUpdateContext ctx) { return prepareSelectedProjectArchive(ctx.getChatId()); }

    @CallbackQuery(CB_NAV_PROJECT_DELETE)
    public Object onNavProjectDelete(TelegramUpdateContext ctx) { return prepareSelectedProjectDelete(ctx.getChatId()); }

    @CallbackQuery(CB_NAV_CONVERSATION_SWITCH)
    public String onNavConversationSwitch(TelegramUpdateContext ctx) { return executeSelectedConversationSwitch(ctx.getChatId()); }

    @CallbackQuery(CB_NAV_CONVERSATION_ARCHIVE)
    public Object onNavConversationArchive(TelegramUpdateContext ctx) { return prepareSelectedConversationArchive(ctx.getChatId()); }

    @CallbackQuery(CB_NAV_CONVERSATION_DELETE)
    public Object onNavConversationDelete(TelegramUpdateContext ctx) { return prepareSelectedConversationDelete(ctx.getChatId()); }

    @CallbackQuery(CB_NAV_BACK_TO_PROJECTS)
    public Object onNavBackToProjects(TelegramUpdateContext ctx) { return rebuildNavigationProjectList(ctx.getChatId()); }

    @CallbackQuery(CB_NAV_BACK_TO_PROJECT)
    public Object onNavBackToProject(TelegramUpdateContext ctx) { return rebuildSelectedProjectView(ctx.getChatId()); }

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

    @CallbackQuery(CB_CONVERSATIONS_PROJECT_PREFIX + "0")
    public Object onConversationsProject0(TelegramUpdateContext ctx) {
        return showProjectConversations(ctx, 0);
    }

    @CallbackQuery(CB_CONVERSATIONS_PROJECT_PREFIX + "1")
    public Object onConversationsProject1(TelegramUpdateContext ctx) {
        return showProjectConversations(ctx, 1);
    }

    @CallbackQuery(CB_CONVERSATIONS_PROJECT_PREFIX + "2")
    public Object onConversationsProject2(TelegramUpdateContext ctx) {
        return showProjectConversations(ctx, 2);
    }

    @CallbackQuery(CB_CONVERSATIONS_PROJECT_PREFIX + "3")
    public Object onConversationsProject3(TelegramUpdateContext ctx) {
        return showProjectConversations(ctx, 3);
    }

    @CallbackQuery(CB_CONVERSATIONS_PROJECT_PREFIX + "4")
    public Object onConversationsProject4(TelegramUpdateContext ctx) {
        return showProjectConversations(ctx, 4);
    }

    @CallbackQuery(CB_CONVERSATIONS_PROJECT_PREFIX + "5")
    public Object onConversationsProject5(TelegramUpdateContext ctx) {
        return showProjectConversations(ctx, 5);
    }

    @CallbackQuery(CB_DELETE_PROJECT_PREFIX + "0")
    public Object onDeleteProject0(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 0, true);
    }

    @CallbackQuery(CB_DELETE_PROJECT_PREFIX + "1")
    public Object onDeleteProject1(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 1, true);
    }

    @CallbackQuery(CB_DELETE_PROJECT_PREFIX + "2")
    public Object onDeleteProject2(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 2, true);
    }

    @CallbackQuery(CB_DELETE_PROJECT_PREFIX + "3")
    public Object onDeleteProject3(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 3, true);
    }

    @CallbackQuery(CB_DELETE_PROJECT_PREFIX + "4")
    public Object onDeleteProject4(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 4, true);
    }

    @CallbackQuery(CB_DELETE_PROJECT_PREFIX + "5")
    public Object onDeleteProject5(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 5, true);
    }

    @CallbackQuery(CB_DELETE_CONVERSATION_PREFIX + "0")
    public Object onDeleteConversation0(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 0, false);
    }

    @CallbackQuery(CB_DELETE_CONVERSATION_PREFIX + "1")
    public Object onDeleteConversation1(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 1, false);
    }

    @CallbackQuery(CB_DELETE_CONVERSATION_PREFIX + "2")
    public Object onDeleteConversation2(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 2, false);
    }

    @CallbackQuery(CB_DELETE_CONVERSATION_PREFIX + "3")
    public Object onDeleteConversation3(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 3, false);
    }

    @CallbackQuery(CB_DELETE_CONVERSATION_PREFIX + "4")
    public Object onDeleteConversation4(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 4, false);
    }

    @CallbackQuery(CB_DELETE_CONVERSATION_PREFIX + "5")
    public Object onDeleteConversation5(TelegramUpdateContext ctx) {
        return prepareDeleteConfirmation(ctx, 5, false);
    }

    @CallbackQuery(CB_ARCHIVE_PROJECT_PREFIX + "0")
    public Object onArchiveProject0(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 0, true);
    }

    @CallbackQuery(CB_ARCHIVE_PROJECT_PREFIX + "1")
    public Object onArchiveProject1(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 1, true);
    }

    @CallbackQuery(CB_ARCHIVE_PROJECT_PREFIX + "2")
    public Object onArchiveProject2(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 2, true);
    }

    @CallbackQuery(CB_ARCHIVE_PROJECT_PREFIX + "3")
    public Object onArchiveProject3(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 3, true);
    }

    @CallbackQuery(CB_ARCHIVE_PROJECT_PREFIX + "4")
    public Object onArchiveProject4(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 4, true);
    }

    @CallbackQuery(CB_ARCHIVE_PROJECT_PREFIX + "5")
    public Object onArchiveProject5(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 5, true);
    }

    @CallbackQuery(CB_ARCHIVE_CONVERSATION_PREFIX + "0")
    public Object onArchiveConversation0(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 0, false);
    }

    @CallbackQuery(CB_ARCHIVE_CONVERSATION_PREFIX + "1")
    public Object onArchiveConversation1(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 1, false);
    }

    @CallbackQuery(CB_ARCHIVE_CONVERSATION_PREFIX + "2")
    public Object onArchiveConversation2(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 2, false);
    }

    @CallbackQuery(CB_ARCHIVE_CONVERSATION_PREFIX + "3")
    public Object onArchiveConversation3(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 3, false);
    }

    @CallbackQuery(CB_ARCHIVE_CONVERSATION_PREFIX + "4")
    public Object onArchiveConversation4(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 4, false);
    }

    @CallbackQuery(CB_ARCHIVE_CONVERSATION_PREFIX + "5")
    public Object onArchiveConversation5(TelegramUpdateContext ctx) {
        return prepareArchiveReasonPrompt(ctx, 5, false);
    }

    @CallbackQuery(CB_CONFIRM_DELETE)
    public String onConfirmDelete(TelegramUpdateContext ctx) {
        return confirmDelete(ctx.getChatId());
    }

    @CallbackQuery(CB_CANCEL_MUTATION)
    public String onCancelMutation(TelegramUpdateContext ctx) {
        sessionService.clearTransientState(ctx.getChatId());
        return "Action annulee.";
    }

    /**
     * Resout la demande HITL en attente avec la decision donnee.
     *
     * @param decision decision a appliquer
     * @return message de confirmation
     */
    private String resolveHitl(ConsentDecision decision) {
        TelegramHumanInteraction telegram = telegramProvider.getIfAvailable();
        if (telegram == null) {
            return "Canal Telegram non configure.";
        }
        boolean resolved = telegram.resolveAsk(decision);
        log.info("Telegram HITL callback - decision={}, resolved={}", decision, resolved);
        return resolved
                ? String.format("Decision enregistree : %s", decision)
                : "Demande expiree ou deja traitee.";
    }

    private String resolveSwitchSuggestion(TelegramUpdateContext ctx, int index) {
        Long chatId = ctx.getChatId();
        Optional<ProjectEntity> project = sessionService.resolveSwitchSuggestion(chatId, index);
        if (project.isEmpty()) {
            return "Cette suggestion n'est plus disponible. Relance /switch.";
        }
        return activateProject(chatId, project.get());
    }

    private Object onNavigationProjectSelected(TelegramUpdateContext ctx, int index) {
        Long chatId = ctx.getChatId();
        Optional<ProjectEntity> project = sessionService.resolveNavigationProjectSuggestion(chatId, index);
        if (project.isEmpty()) {
            return "Cette suggestion n'est plus disponible. Relance la commande.";
        }
        ProjectEntity targetProject = project.get();
        List<ConversationEntity> conversations = sessionService
                .listOpenConversationsForProject(targetProject.getId(), MAX_NAV_SUGGESTIONS);
        sessionService.selectNavigationProject(
                chatId,
                targetProject.getId(),
                conversations.stream().map(ConversationEntity::getId).toList());
        sessionService.setConversationSuggestions(chatId, conversations.stream().map(ConversationEntity::getId).toList());
        return buildNavigationProjectDetailView(chatId, targetProject, conversations);
    }

    private Object onNavigationConversationSelected(TelegramUpdateContext ctx, int index) {
        Long chatId = ctx.getChatId();
        Optional<ConversationEntity> conversation = sessionService.resolveNavigationConversationSuggestion(chatId, index);
        if (conversation.isEmpty()) {
            return "Cette conversation n'est plus disponible. Relance la commande.";
        }
        ConversationEntity targetConversation = conversation.get();
        Optional<TelegramSessionService.NavigationState> navigation = sessionService.getNavigationState(chatId);
        TelegramSessionService.NavigationIntent intent = navigation
                .map(TelegramSessionService.NavigationState::intent)
                .orElse(TelegramSessionService.NavigationIntent.BROWSE_PROJECTS);
        if (intent == TelegramSessionService.NavigationIntent.SWITCH) {
            return activateConversation(chatId, targetConversation);
        }
        if (intent == TelegramSessionService.NavigationIntent.ARCHIVE) {
            return prepareConversationArchive(chatId, targetConversation);
        }
        if (intent == TelegramSessionService.NavigationIntent.DELETE) {
            return prepareConversationDelete(chatId, targetConversation);
        }
        sessionService.selectNavigationConversation(chatId, targetConversation.getId());
        return buildConversationActionView(targetConversation);
    }

    private Object showProjectConversations(TelegramUpdateContext ctx, int index) {
        Long chatId = ctx.getChatId();
        Optional<ProjectEntity> project = sessionService.resolveConversationProjectSuggestion(chatId, index);
        if (project.isEmpty()) {
            return "Cette suggestion n'est plus disponible. Relance /conversations.";
        }
        ProjectEntity targetProject = project.get();
        List<ConversationEntity> conversations = sessionService
                .listOpenConversationsForProject(targetProject.getId(), MAX_CONVERSATION_SUGGESTIONS);
        if (conversations.isEmpty()) {
            return "Aucune conversation ouverte sur le projet " + targetProject.getName() + ".";
        }
        sessionService.setConversationSuggestions(chatId, conversations.stream().map(ConversationEntity::getId).toList());
        StringBuilder sb = new StringBuilder("Conversations - ").append(targetProject.getName()).append("\n");
        for (int i = 0; i < conversations.size(); i++) {
            ConversationEntity conversation = conversations.get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(resolveConversationDisplayTitle(conversation))
                    .append(" (")
                    .append(formatConversationDate(conversation.getLastMessageAt(), conversation.getStartedAt()))
                    .append(")\n");
        }
        sb.append("\nTape /conv <n> pour reprendre une conversation.");
        log.info("Telegram /conversations - chatId={}, projectId={}, count={}",
                chatId, targetProject.getId(), conversations.size());
        return sb.toString();
    }

    private Object rebuildNavigationProjectList(Long chatId) {
        TelegramSessionService.NavigationIntent intent = sessionService.getNavigationState(chatId)
                .map(TelegramSessionService.NavigationState::intent)
                .orElse(TelegramSessionService.NavigationIntent.BROWSE_PROJECTS);
        List<ProjectEntity> activeProjects = sessionService.listActiveProjectsByRecentActivity().stream()
                .limit(MAX_NAV_SUGGESTIONS)
                .toList();
        if (activeProjects.isEmpty()) {
            sessionService.clearTransientState(chatId);
            return "Aucun projet actif.";
        }
        sessionService.openNavigation(chatId, intent, activeProjects.stream().map(ProjectEntity::getId).toList());
        return buildNavigationProjectSelectionView(chatId, resolveNavigationProjectListTitle(intent), activeProjects);
    }

    private Object rebuildSelectedProjectView(Long chatId) {
        Optional<TelegramSessionService.NavigationState> state = sessionService.getNavigationState(chatId);
        if (state.isEmpty() || state.get().selectedProjectId() == null) {
            return rebuildNavigationProjectList(chatId);
        }
        ProjectService projectService = projectServiceProvider.getIfAvailable();
        if (projectService == null) {
            return "ProjectService non disponible.";
        }
        ProjectEntity project = projectService.findById(state.get().selectedProjectId());
        List<ConversationEntity> conversations = sessionService
                .listOpenConversationsForProject(project.getId(), MAX_NAV_SUGGESTIONS);
        sessionService.selectNavigationProject(
                chatId,
                project.getId(),
                conversations.stream().map(ConversationEntity::getId).toList());
        return buildNavigationProjectDetailView(chatId, project, conversations);
    }

    private String activateProject(Long chatId, ProjectEntity project) {
        sessionService.setActiveProject(chatId, project.getId(), project.getName());
        long openConvCount = sessionService.countOpenConversations(project.getId());
        String convLabel = openConvCount == 1 ? "1 conversation ouverte" :
                openConvCount + " conversations ouvertes";
        log.info("Switch projet Telegram - attente du premier message pour creer la conversation, chatId={}, projectId={}",
                chatId, project.getId());
        return String.format(
                "Projet actif : *%s*\n%s\nLa conversation sera creee au premier message.",
                project.getName(),
                convLabel);
    }

    private String activateConversation(Long chatId, ConversationEntity conversation) {
        String projectName = resolveProjectName(conversation.getProjectId());
        sessionService.setActiveProject(chatId, conversation.getProjectId(), projectName);
        sessionService.setActiveConversationId(chatId, conversation.getId());
        return "Action : switch"
                + "\nProjet : " + projectName
                + "\nConversation : " + resolveConversationDisplayTitle(conversation);
    }

    private TelegramView buildSwitchSuggestionView(String query, List<ProjectEntity> candidates) {
        String text = query == null || query.isBlank()
                ? "Selectionne le projet actif :"
                : String.format("Plusieurs projets ressemblent a '%s'. Selectionne le bon :", query);

        List<List<TelegramButtonView>> buttons = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ProjectEntity project = candidates.get(i);
            buttons.add(List.of(new TelegramButtonView("📁 " + project.getName(), CB_SWITCH_PREFIX + i)));
        }

        return TelegramView.builder()
                .text(text)
                .buttons(buttons)
                .build();
    }

    private TelegramView buildNavigationProjectSelectionView(Long chatId, String text, List<ProjectEntity> projects) {
        List<String> projectIds = projects.stream().map(ProjectEntity::getId).toList();
        Map<String, Long> openCountByProject = sessionService.countOpenConversationsByProjects(projectIds);
        Optional<String> activeProjectId = sessionService.resolveProjectId(chatId);
        List<List<TelegramButtonView>> buttons = new ArrayList<>();
        for (int i = 0; i < projects.size(); i++) {
            ProjectEntity project = projects.get(i);
            long openCount = openCountByProject.getOrDefault(project.getId(), 0L);
            String activeMarker = activeProjectId.filter(project.getId()::equals).isPresent()
                    ? " 🟢"
                    : "";
            String label = String.format("📁 %s%s (%d)", project.getName(), activeMarker, openCount);
            buttons.add(List.of(new TelegramButtonView(label, CB_NAV_PROJECT_PREFIX + i)));
        }
        buttons.add(List.of(new TelegramButtonView("❌ Annuler", CB_CANCEL_MUTATION)));
        return TelegramView.builder()
                .text(text)
                .buttons(buttons)
                .build();
    }

    private TelegramView buildNavigationProjectDetailView(
            Long chatId,
            ProjectEntity project,
            List<ConversationEntity> conversations) {
        boolean protectedProject = isProtectedProject(project);
        StringBuilder text = new StringBuilder("Projet : ").append(project.getName());
        long openCount = conversations.size();
        text.append("\nConversations ouvertes : ").append(openCount);
        if (sessionService.getActiveProjectId(chatId).filter(project.getId()::equals).isPresent()) {
            text.append("\nProjet actif dans cette session.");
        }
        if (protectedProject) {
            text.append("\nProjet systeme protege : archivage et suppression interdits.");
        }
        if (conversations.isEmpty()) {
            text.append("\nAucune conversation ouverte.");
        } else {
            text.append("\n\nSelectionne une conversation :");
        }

        List<List<TelegramButtonView>> buttons = new ArrayList<>();
        if (protectedProject) {
            buttons.add(List.of(new TelegramButtonView("🔀 Switcher", CB_NAV_PROJECT_SWITCH)));
        } else {
            buttons.add(List.of(
                    new TelegramButtonView("🔀 Switcher", CB_NAV_PROJECT_SWITCH),
                    new TelegramButtonView("📦 Archiver", CB_NAV_PROJECT_ARCHIVE),
                    new TelegramButtonView("🗑️ Supprimer", CB_NAV_PROJECT_DELETE)));
        }
        for (int i = 0; i < conversations.size(); i++) {
            ConversationEntity conversation = conversations.get(i);
            String label = "💬 " + resolveConversationDisplayTitle(conversation)
                    + " (" + formatConversationDate(conversation.getLastMessageAt(), conversation.getStartedAt()) + ")";
            buttons.add(List.of(new TelegramButtonView(label, CB_NAV_CONVERSATION_PREFIX + i)));
        }
        buttons.add(List.of(
                new TelegramButtonView("⬅️ Projets", CB_NAV_BACK_TO_PROJECTS),
                new TelegramButtonView("❌ Annuler", CB_CANCEL_MUTATION)));
        return TelegramView.builder()
                .text(text.toString())
                .buttons(buttons)
                .build();
    }

    private TelegramView buildConversationActionView(ConversationEntity conversation) {
        String projectName = resolveProjectName(conversation.getProjectId());
        return TelegramView.builder()
                .text("Projet : " + projectName
                        + "\nConversation : " + resolveConversationDisplayTitle(conversation)
                        + "\nChoisis l'action a effectuer :")
                .buttons(List.of(
                        List.of(
                                new TelegramButtonView("💬 Switcher", CB_NAV_CONVERSATION_SWITCH),
                                new TelegramButtonView("📦 Archiver", CB_NAV_CONVERSATION_ARCHIVE),
                                new TelegramButtonView("🗑️ Supprimer", CB_NAV_CONVERSATION_DELETE)),
                        List.of(
                                new TelegramButtonView("⬅️ Projet", CB_NAV_BACK_TO_PROJECT),
                        new TelegramButtonView("❌ Annuler", CB_CANCEL_MUTATION))))
                .build();
    }

    private String resolveNavigationProjectListTitle(TelegramSessionService.NavigationIntent intent) {
        return switch (intent) {
            case BROWSE_CONVERSATIONS -> "Selectionne le projet pour voir ses conversations ouvertes :";
            case SWITCH -> "Selectionne le projet a activer :";
            case ARCHIVE -> "Selectionne le projet, puis la conversation a archiver si besoin :";
            case DELETE -> "Selectionne le projet, puis la conversation a supprimer si besoin :";
            default -> "Projets actifs :";
        };
    }

    private String executeSelectedProjectSwitch(Long chatId) {
        Optional<ProjectEntity> project = resolveSelectedNavigationProject(chatId);
        if (project.isEmpty()) {
            return "Aucun projet selectionne.";
        }
        return activateProject(chatId, project.get());
    }

    private String executeSelectedConversationSwitch(Long chatId) {
        Optional<ConversationEntity> conversation = resolveSelectedNavigationConversation(chatId);
        if (conversation.isEmpty()) {
            return "Aucune conversation selectionnee.";
        }
        return activateConversation(chatId, conversation.get());
    }

    private Object prepareSelectedProjectArchive(Long chatId) {
        Optional<ProjectEntity> project = resolveSelectedNavigationProject(chatId);
        if (project.isEmpty()) {
            return "Aucun projet selectionne.";
        }
        ProjectEntity target = project.get();
        sessionService.setPendingArchiveReason(chatId, new TelegramSessionService.PendingAction(
                TelegramSessionService.PendingActionType.ARCHIVE_PROJECT,
                target.getId(),
                target.getName()));
        return buildArchiveReasonView(
                "Envoie la raison d'archivage pour le projet \"" + target.getName()
                        + "\".\nToutes ses conversations ouvertes seront archivees.");
    }

    private Object prepareSelectedProjectDelete(Long chatId) {
        Optional<ProjectEntity> project = resolveSelectedNavigationProject(chatId);
        if (project.isEmpty()) {
            return "Aucun projet selectionne.";
        }
        ProjectEntity target = project.get();
        sessionService.setPendingDeleteConfirmation(chatId, new TelegramSessionService.PendingAction(
                TelegramSessionService.PendingActionType.DELETE_PROJECT,
                target.getId(),
                target.getName()));
        return buildDeleteConfirmationView(
                "Confirmer la suppression du projet \"" + target.getName()
                        + "\" ?\nToutes ses conversations, leur memoire Spring AI et le dossier physique seront supprimes.");
    }

    private Object prepareSelectedConversationArchive(Long chatId) {
        Optional<ConversationEntity> conversation = resolveSelectedNavigationConversation(chatId);
        if (conversation.isEmpty()) {
            return "Aucune conversation selectionnee.";
        }
        return prepareConversationArchive(chatId, conversation.get());
    }

    private Object prepareSelectedConversationDelete(Long chatId) {
        Optional<ConversationEntity> conversation = resolveSelectedNavigationConversation(chatId);
        if (conversation.isEmpty()) {
            return "Aucune conversation selectionnee.";
        }
        return prepareConversationDelete(chatId, conversation.get());
    }

    private Object prepareConversationArchive(Long chatId, ConversationEntity conversation) {
        String projectName = resolveProjectName(conversation.getProjectId());
        sessionService.setPendingArchiveReason(chatId, new TelegramSessionService.PendingAction(
                TelegramSessionService.PendingActionType.ARCHIVE_CONVERSATION,
                conversation.getId(),
                resolveConversationDisplayTitle(conversation)));
        return buildArchiveReasonView(
                "Projet : " + projectName
                        + "\nConversation : " + resolveConversationDisplayTitle(conversation)
                        + "\nEnvoie la raison d'archivage.");
    }

    private Object prepareConversationDelete(Long chatId, ConversationEntity conversation) {
        String projectName = resolveProjectName(conversation.getProjectId());
        sessionService.setPendingDeleteConfirmation(chatId, new TelegramSessionService.PendingAction(
                TelegramSessionService.PendingActionType.DELETE_CONVERSATION,
                conversation.getId(),
                resolveConversationDisplayTitle(conversation)));
        return buildDeleteConfirmationView(
                "Projet : " + projectName
                        + "\nConversation : " + resolveConversationDisplayTitle(conversation)
                        + "\nConfirmer la suppression ?\nSa memoire Spring AI sera purgee.");
    }

    private Optional<ProjectEntity> resolveSelectedNavigationProject(Long chatId) {
        Optional<TelegramSessionService.NavigationState> state = sessionService.getNavigationState(chatId);
        if (state.isEmpty() || state.get().selectedProjectId() == null) {
            return Optional.empty();
        }
        ProjectService projectService = projectServiceProvider.getIfAvailable();
        if (projectService == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(projectService.findById(state.get().selectedProjectId()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private boolean isProtectedProject(ProjectEntity project) {
        ProjectService projectService = projectServiceProvider.getIfAvailable();
        return projectService != null && projectService.isProtectedProject(project);
    }

    private Optional<ConversationEntity> resolveSelectedNavigationConversation(Long chatId) {
        Optional<TelegramSessionService.NavigationState> state = sessionService.getNavigationState(chatId);
        if (state.isEmpty() || state.get().selectedConversationId() == null) {
            return Optional.empty();
        }
        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(conversationService.getConversation(state.get().selectedConversationId()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String firstArg(TelegramUpdateContext ctx) {
        if (ctx.getArgs() == null || ctx.getArgs().isEmpty()) {
            return null;
        }
        return ctx.getArgs().getFirst();
    }

    private String normalizeScope(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase();
        if ("conversation".equals(normalized)) {
            return "conv";
        }
        return normalized;
    }

    private Object buildDeleteProjectSelection(Long chatId) {
        ProjectService projectService = projectServiceProvider.getIfAvailable();
        if (projectService == null) {
            return "ProjectService non disponible.";
        }
        List<ProjectEntity> projects = projectService.findAll().stream()
                .sorted(Comparator.comparing(ProjectEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_MUTATION_PROJECT_SUGGESTIONS)
                .toList();
        if (projects.isEmpty()) {
            return "Aucun projet a supprimer.";
        }
        sessionService.setDeleteProjectSuggestions(chatId, projects.stream().map(ProjectEntity::getId).toList());
        return buildProjectSelectionView("Supprimer quel projet ?", projects, CB_DELETE_PROJECT_PREFIX);
    }

    private Object buildDeleteConversationSelection(Long chatId) {
        Optional<String> resolvedProjectId = sessionService.resolveProjectId(chatId);
        if (resolvedProjectId.isEmpty()) {
            return "Aucun projet actif. Utilise /switch pour en selectionner un.";
        }
        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService == null) {
            return "ConversationService non disponible.";
        }
        List<ConversationEntity> conversations = conversationService
                .listByProject(resolvedProjectId.get(), "ALL").stream()
                .limit(MAX_MUTATION_CONVERSATION_SUGGESTIONS)
                .toList();
        if (conversations.isEmpty()) {
            return "Aucune conversation a supprimer sur ce projet.";
        }
        sessionService.setDeleteConversationSuggestions(
                chatId, conversations.stream().map(ConversationEntity::getId).toList());
        return buildConversationSelectionView("Supprimer quelle conversation ?", conversations,
                CB_DELETE_CONVERSATION_PREFIX);
    }

    private Object buildArchiveProjectSelection(Long chatId) {
        List<ProjectEntity> projects = sessionService.listActiveProjects().stream()
                .sorted(Comparator.comparing(ProjectEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_MUTATION_PROJECT_SUGGESTIONS)
                .toList();
        if (projects.isEmpty()) {
            return "Aucun projet actif a archiver.";
        }
        sessionService.setArchiveProjectSuggestions(chatId, projects.stream().map(ProjectEntity::getId).toList());
        return buildProjectSelectionView("Archiver quel projet ?", projects, CB_ARCHIVE_PROJECT_PREFIX);
    }

    private Object buildArchiveConversationSelection(Long chatId) {
        Optional<String> resolvedProjectId = sessionService.resolveProjectId(chatId);
        if (resolvedProjectId.isEmpty()) {
            return "Aucun projet actif. Utilise /switch pour en selectionner un.";
        }
        List<ConversationEntity> conversations = sessionService
                .listOpenConversationsForProject(resolvedProjectId.get(), MAX_MUTATION_CONVERSATION_SUGGESTIONS);
        if (conversations.isEmpty()) {
            return "Aucune conversation ouverte a archiver sur ce projet.";
        }
        sessionService.setArchiveConversationSuggestions(
                chatId, conversations.stream().map(ConversationEntity::getId).toList());
        return buildConversationSelectionView("Archiver quelle conversation ?", conversations,
                CB_ARCHIVE_CONVERSATION_PREFIX);
    }

    private TelegramView buildProjectSelectionView(String text, List<ProjectEntity> projects, String callbackPrefix) {
        List<List<TelegramButtonView>> buttons = new ArrayList<>();
        for (int i = 0; i < projects.size(); i++) {
            ProjectEntity project = projects.get(i);
            String label = project.getStatus() == fr.ses10doigts.mm.starter.project.ProjectStatus.ARCHIVED
                    ? "📁 " + project.getName() + " [ARCHIVED]"
                    : "📁 " + project.getName();
            buttons.add(List.of(new TelegramButtonView(label, callbackPrefix + i)));
        }
        buttons.add(List.of(new TelegramButtonView("❌ Annuler", CB_CANCEL_MUTATION)));
        return TelegramView.builder()
                .text(text)
                .buttons(buttons)
                .build();
    }

    private TelegramView buildConversationSelectionView(
            String text,
            List<ConversationEntity> conversations,
            String callbackPrefix) {
        List<List<TelegramButtonView>> buttons = new ArrayList<>();
        for (int i = 0; i < conversations.size(); i++) {
            ConversationEntity conversation = conversations.get(i);
            String label = "💬 " + resolveConversationDisplayTitle(conversation);
            if (conversation.getStatus() == fr.ses10doigts.mm.starter.conversation.ConversationStatus.ARCHIVED) {
                label += " [ARCHIVED]";
            }
            buttons.add(List.of(new TelegramButtonView(label, callbackPrefix + i)));
        }
        buttons.add(List.of(new TelegramButtonView("❌ Annuler", CB_CANCEL_MUTATION)));
        return TelegramView.builder()
                .text(text)
                .buttons(buttons)
                .build();
    }

    private Object prepareDeleteConfirmation(TelegramUpdateContext ctx, int index, boolean projectAction) {
        Long chatId = ctx.getChatId();
        if (projectAction) {
            Optional<ProjectEntity> project = sessionService.resolveDeleteProjectSuggestion(chatId, index);
            if (project.isEmpty()) {
                return "Selection introuvable. Relance /delete project.";
            }
            ProjectEntity target = project.get();
            sessionService.setPendingDeleteConfirmation(chatId, new TelegramSessionService.PendingAction(
                    TelegramSessionService.PendingActionType.DELETE_PROJECT,
                    target.getId(),
                    target.getName()));
            return buildDeleteConfirmationView(
                    "Confirmer la suppression du projet \"" + target.getName()
                            + "\" ?\nToutes ses conversations, leur memoire Spring AI et le dossier physique seront supprimes.");
        }

        Optional<ConversationEntity> conversation = sessionService.resolveDeleteConversationSuggestion(chatId, index);
        if (conversation.isEmpty()) {
            return "Selection introuvable. Relance /delete conv.";
        }
        ConversationEntity target = conversation.get();
        sessionService.setPendingDeleteConfirmation(chatId, new TelegramSessionService.PendingAction(
                TelegramSessionService.PendingActionType.DELETE_CONVERSATION,
                target.getId(),
                resolveConversationDisplayTitle(target)));
        return buildDeleteConfirmationView(
                "Confirmer la suppression de la conversation \"" + resolveConversationDisplayTitle(target)
                        + "\" ?\nSa memoire Spring AI sera purgee.");
    }

    private TelegramView buildDeleteConfirmationView(String text) {
        return TelegramView.builder()
                .text(text)
                .buttons(List.of(
                        List.of(new TelegramButtonView("✅ Confirmer", CB_CONFIRM_DELETE)),
                        List.of(new TelegramButtonView("❌ Annuler", CB_CANCEL_MUTATION))))
                .build();
    }

    private Object prepareArchiveReasonPrompt(TelegramUpdateContext ctx, int index, boolean projectAction) {
        Long chatId = ctx.getChatId();
        if (projectAction) {
            Optional<ProjectEntity> project = sessionService.resolveArchiveProjectSuggestion(chatId, index);
            if (project.isEmpty()) {
                return "Selection introuvable. Relance /archive project.";
            }
            ProjectEntity target = project.get();
            sessionService.setPendingArchiveReason(chatId, new TelegramSessionService.PendingAction(
                    TelegramSessionService.PendingActionType.ARCHIVE_PROJECT,
                    target.getId(),
                    target.getName()));
            return buildArchiveReasonView(
                    "Envoie la raison d'archivage pour le projet \"" + target.getName()
                            + "\".\nToutes ses conversations ouvertes seront archivees.");
        }

        Optional<ConversationEntity> conversation = sessionService.resolveArchiveConversationSuggestion(chatId, index);
        if (conversation.isEmpty()) {
            return "Selection introuvable. Relance /archive conv.";
        }
        ConversationEntity target = conversation.get();
        sessionService.setPendingArchiveReason(chatId, new TelegramSessionService.PendingAction(
                TelegramSessionService.PendingActionType.ARCHIVE_CONVERSATION,
                target.getId(),
                resolveConversationDisplayTitle(target)));
        return buildArchiveReasonView(
                "Envoie la raison d'archivage pour la conversation \"" + resolveConversationDisplayTitle(target) + "\".");
    }

    private TelegramView buildArchiveReasonView(String text) {
        return TelegramView.builder()
                .text(text)
                .buttons(List.of(List.of(new TelegramButtonView("❌ Annuler", CB_CANCEL_MUTATION))))
                .build();
    }

    private String confirmDelete(Long chatId) {
        Optional<TelegramSessionService.PendingAction> pending = sessionService.getPendingDeleteConfirmation(chatId);
        if (pending.isEmpty()) {
            return "Aucune suppression en attente.";
        }

        TelegramSessionService.PendingAction action = pending.get();
        sessionService.clearTransientState(chatId);
        if (action.type() == TelegramSessionService.PendingActionType.DELETE_PROJECT) {
            ProjectService projectService = projectServiceProvider.getIfAvailable();
            if (projectService == null) {
                return "ProjectService non disponible.";
            }
            projectService.delete(action.targetId());
            if (sessionService.getActiveProjectId(chatId).filter(id -> id.equals(action.targetId())).isPresent()) {
                sessionService.clearActiveProject(chatId);
            }
            return "Action : suppression"
                    + "\nProjet : " + action.targetLabel();
        }

        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService == null) {
            return "ConversationService non disponible.";
        }
        ConversationEntity conversation = conversationService.getConversation(action.targetId());
        String projectName = resolveProjectName(conversation.getProjectId());
        conversationService.delete(action.targetId());
        if (sessionService.getActiveConversationId(chatId).filter(id -> id.equals(action.targetId())).isPresent()) {
            sessionService.clearActiveConversationId(chatId);
        }
        return "Action : suppression"
                + "\nProjet : " + projectName
                + "\nConversation : " + action.targetLabel();
    }

    private Optional<String> consumePendingArchiveReason(TelegramUpdateContext ctx, String text) {
        Long chatId = ctx.getChatId();
        Optional<TelegramSessionService.PendingAction> pending = sessionService.getPendingArchiveReason(chatId);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        String reason = text == null ? "" : text.trim();
        if (reason.isBlank()) {
            return Optional.of("La raison d'archivage ne peut pas etre vide.");
        }

        TelegramSessionService.PendingAction action = pending.get();
        sessionService.clearTransientState(chatId);
        if (action.type() == TelegramSessionService.PendingActionType.ARCHIVE_PROJECT) {
            ProjectService projectService = projectServiceProvider.getIfAvailable();
            if (projectService == null) {
                return Optional.of("ProjectService non disponible.");
            }
            projectService.archive(action.targetId(), reason);
            if (sessionService.getActiveProjectId(chatId).filter(id -> id.equals(action.targetId())).isPresent()) {
                sessionService.clearActiveProject(chatId);
            }
            return Optional.of("Action : archivage"
                    + "\nProjet : " + action.targetLabel()
                    + "\nRaison : " + reason);
        }

        ConversationService conversationService = conversationServiceProvider.getIfAvailable();
        if (conversationService == null) {
            return Optional.of("ConversationService non disponible.");
        }
        ConversationEntity conversation = conversationService.getConversation(action.targetId());
        String projectName = resolveProjectName(conversation.getProjectId());
        conversationService.archive(action.targetId(), reason);
        if (sessionService.getActiveConversationId(chatId).filter(id -> id.equals(action.targetId())).isPresent()) {
            sessionService.clearActiveConversationId(chatId);
        }
        return Optional.of("Action : archivage"
                + "\nProjet : " + projectName
                + "\nConversation : " + action.targetLabel()
                + "\nRaison : " + reason);
    }

    private String resolveProjectName(String projectId) {
        ProjectService projectService = projectServiceProvider.getIfAvailable();
        if (projectService == null) {
            return projectId;
        }
        try {
            return projectService.findById(projectId).getName();
        } catch (RuntimeException ex) {
            return projectId;
        }
    }

    private boolean isSystemCommand(String text) {
        return text.stripLeading().startsWith("/");
    }

    private String resolveTelegramConversationId(
            Long chatId,
            String projectId,
            ConversationService conversationService) {
        String conversationId = sessionService.getActiveConversationId(chatId).orElse(null);
        if (conversationId == null) {
            try {
                ConversationEntity conversation = conversationService.startConversation(projectId);
                sessionService.setActiveConversationId(chatId, conversation.getId());
                log.debug("Telegram chat - nouvelle conversationId injectee={}, projectId={}",
                        conversation.getId(), projectId);
                return conversation.getId();
            } catch (Exception ex) {
                log.warn("Telegram chat - impossible de creer la conversation pour projectId={} : {}",
                        projectId, ex.getMessage());
                return null;
            }
        }

        try {
            ConversationEntity conversation = conversationService.getConversation(conversationId);
            if (conversation.getStatus() != fr.ses10doigts.mm.starter.conversation.ConversationStatus.OPEN) {
                sessionService.clearActiveConversationId(chatId);
                sessionService.clearConversationSuggestions(chatId);
                log.info("Telegram chat - conversation active archivee, chatId={}, conversationId={}",
                        chatId, conversationId);
                return null;
            }
            log.info("Telegram chat - reutilisation conversation active chatId={}, conversationId={}",
                    chatId, conversationId);
            return conversationId;
        } catch (ArchivedConversationReadOnlyException ex) {
            sessionService.clearActiveConversationId(chatId);
            sessionService.clearConversationSuggestions(chatId);
            return null;
        } catch (RuntimeException ex) {
            sessionService.clearActiveConversationId(chatId);
            log.warn("Telegram chat - conversation active invalide, chatId={}, conversationId={}, reason={}",
                    chatId, conversationId, ex.getMessage());
            return null;
        }
    }

    private String resolveConversationDisplayTitle(ConversationEntity conversation) {
        if (conversation.getTitle() == null || conversation.getTitle().isBlank()) {
            return "Conversation sans titre";
        }
        return conversation.getTitle();
    }

    private String formatConversationDate(String lastMessageAt, String startedAt) {
        LocalDate referenceDate = toLocalDate(lastMessageAt != null ? lastMessageAt : startedAt);
        if (referenceDate == null) {
            return "date inconnue";
        }
        LocalDate today = LocalDate.now();
        if (referenceDate.equals(today)) {
            return "aujourd'hui";
        }
        if (referenceDate.equals(today.minusDays(1))) {
            return "hier";
        }
        return referenceDate.getDayOfMonth() + "/" + referenceDate.getMonthValue();
    }

    private LocalDate toLocalDate(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(isoInstant).atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (DateTimeParseException ex) {
            log.debug("Date conversation illisible - value='{}'", isoInstant, ex);
            return null;
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "?";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
