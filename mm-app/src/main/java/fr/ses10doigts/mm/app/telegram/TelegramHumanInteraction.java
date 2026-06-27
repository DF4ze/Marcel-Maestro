package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.core.hitl.AgentNotification;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlRequest;
import fr.ses10doigts.mm.core.hitl.NotificationLevel;
import fr.ses10doigts.mm.starter.hitl.CancellableHumanInteraction;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.telegrambots.model.TelegramButtonView;
import fr.ses10doigts.telegrambots.model.TelegramMessageFormat;
import fr.ses10doigts.telegrambots.model.TelegramView;
import fr.ses10doigts.telegrambots.service.sender.SimpleTelegramSender;
import java.util.ArrayList;
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
 *
 * <p><strong>E2-M5 — Préfixe [NomProjet] :</strong><br>
 * Toutes les notifications {@link #notify} sont préfixées avec {@code [NomProjet]}
 * pour contextualiser le message Telegram par projet. Le nom est résolu depuis
 * {@link ProjectRepository} via l'{@link fr.ses10doigts.mm.core.agent.AgentContext#projectId()}.
 * Si le projet est introuvable (suppression concurrente), l'ID brut est affiché
 * plutôt que de planter (dégradation gracieuse).</p>
 */
@Slf4j
public class TelegramHumanInteraction implements CancellableHumanInteraction {

    /** Préfixe des callbackData HITL (valeurs fixes pour le match exact @CallbackQuery). */
    static final String CB_PREFIX         = "hitl_";
    static final String CB_ALLOW_ONCE     = CB_PREFIX + "allow_once";
    static final String CB_DENY           = CB_PREFIX + "deny";
    // Scope Stricte (chemin exact / commande complète)
    static final String CB_STRICT_SESSION = CB_PREFIX + "strict_session";
    static final String CB_STRICT_PROJECT = CB_PREFIX + "strict_project";
    static final String CB_STRICT_ALWAYS  = CB_PREFIX + "strict_always";
    // Scope Local (répertoire / programme)
    static final String CB_LOCAL_SESSION  = CB_PREFIX + "local_session";
    static final String CB_LOCAL_PROJECT  = CB_PREFIX + "local_project";
    static final String CB_LOCAL_ALWAYS   = CB_PREFIX + "local_always";
    // Scope Large (outil entier)
    static final String CB_LARGE_SESSION  = CB_PREFIX + "large_session";
    static final String CB_LARGE_PROJECT  = CB_PREFIX + "large_project";
    static final String CB_LARGE_ALWAYS   = CB_PREFIX + "large_always";

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
     * Repository projet pour résoudre le nom depuis le projectId (E2-M5).
     * Nullable : si absent (tests ou contexte sans DB), le préfixe affiche l'ID brut.
     */
    private final ProjectRepository projectRepository;

    /**
     * Future de la demande HITL en attente. Un seul à la fois côté Telegram.
     * Un nouveau ask() annule le précédent s'il est encore en attente.
     */
    private final AtomicReference<CompletableFuture<ConsentDecision>> pendingAsk =
            new AtomicReference<>();

    /**
     * Construit un adaptateur Telegram avec résolution du nom de projet (E2-M5).
     *
     * @param sender             sender explicite (botId en paramètre à chaque appel)
     * @param botId              identifiant du bot MM dans le registre telegram-bots-mvc
     * @param chatId             ID du chat Telegram pour les messages
     * @param askTimeoutSeconds  timeout en secondes pour ask()
     * @param projectRepository  repository projet pour le préfixe [NomProjet] (nullable)
     */
    public TelegramHumanInteraction(SimpleTelegramSender sender, String botId,
                                     Long chatId, int askTimeoutSeconds,
                                     ProjectRepository projectRepository) {
        this.sender = sender;
        this.botId = botId;
        this.chatId = chatId;
        this.askTimeoutSeconds = askTimeoutSeconds;
        this.projectRepository = projectRepository;
        log.info("TelegramHumanInteraction initialisé — botId={}, chatId={}, timeout={}s",
                botId, chatId, askTimeoutSeconds);
    }

    /**
     * Envoie une notification formatée sur Telegram en texte plat.
     *
     * <p>Utilise volontairement {@code sendMessage} (pas de Markdown) : le contenu des
     * notifications peut provenir du LLM et contenir des caractères spéciaux ({@code *},
     * {@code _}, {@code [}…) qui cassent le parser Telegram Markdown v1.</p>
     *
     * @param notification notification à pousser
     */
    @Override
    public void notify(AgentNotification notification) {
        String emoji = LEVEL_EMOJI.getOrDefault(notification.level(), "ℹ️");
        String ctx = formatContext(notification);
        String projectPrefix = resolveProjectPrefix(notification);

        String header = projectPrefix.isBlank()
                ? String.format("%s %s", emoji, notification.title())
                : String.format("%s %s %s", projectPrefix, emoji, notification.title());

        String text = ctx.isBlank()
                ? String.format("%s\n%s", header, notification.message())
                : String.format("%s\n%s\n%s", header, notification.message(), ctx);

        log.debug("Telegram notify() — level={}, title={}, prefix='{}'",
                notification.level(), notification.title(), projectPrefix);
        try {
            sender.sendMessage(botId, chatId, text);
        } catch (Exception e) {
            log.warn("Telegram notify() — échec envoi : {}", e.getMessage());
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
     * Envoie le message HITL avec jusqu'à 4 lignes de boutons contextuels.
     *
     * <p>Layout :</p>
     * <pre>
     * Ligne 1 : [🔂 Une fois]  [❌ Refuser]
     * Ligne 2 : [🎯 &lt;exact&gt;/conv]  [🎯 &lt;exact&gt;/proj]  [🎯 &lt;exact&gt;/∞]   (si scope strict dispo)
     * Ligne 3 : [📁 &lt;local&gt;/conv]  [📁 &lt;local&gt;/proj]  [📁 &lt;local&gt;/∞]   (si scope local dispo)
     * Ligne 4 : [🌐 &lt;outil&gt;/conv]  [🌐 &lt;outil&gt;/proj]  [🌐 &lt;outil&gt;/∞]
     * </pre>
     *
     * <p>Utilise {@link TelegramMessageFormat#PLAIN} pour éviter tout conflit avec
     * les caractères spéciaux présents dans la question (noms de fichiers, chemins…).</p>
     */
    private void sendAskMessage(HitlRequest request) {
        String text = String.format("⚠️ Validation requise [%s]\n\n%s",
                request.riskLevel(),
                request.question());

        List<List<TelegramButtonView>> buttons = new ArrayList<>();

        // Ligne 1 : une fois + refuser
        buttons.add(List.of(
                new TelegramButtonView("🔂 Une fois", CB_ALLOW_ONCE),
                new TelegramButtonView("❌ Refuser", CB_DENY)
        ));

        // Ligne 2 : stricte (chemin exact / commande complète)
        if (request.strictScopeLabel() != null) {
            String lbl = btnLabel(request.strictScopeLabel(), 14);
            buttons.add(List.of(
                    new TelegramButtonView("🎯 " + lbl + " / conv", CB_STRICT_SESSION),
                    new TelegramButtonView("🎯 " + lbl + " / proj", CB_STRICT_PROJECT),
                    new TelegramButtonView("🎯 " + lbl + " / ∞",    CB_STRICT_ALWAYS)
            ));
        }

        // Ligne 3 : local (répertoire / programme)
        if (request.localScopeLabel() != null) {
            String lbl = btnLabel(request.localScopeLabel(), 14);
            buttons.add(List.of(
                    new TelegramButtonView("📁 " + lbl + " / conv", CB_LOCAL_SESSION),
                    new TelegramButtonView("📁 " + lbl + " / proj", CB_LOCAL_PROJECT),
                    new TelegramButtonView("📁 " + lbl + " / ∞",    CB_LOCAL_ALWAYS)
            ));
        }

        // Ligne 4 : large (outil entier — toujours affiché)
        String toolLbl = btnLabel(request.toolName() != null ? request.toolName() : "outil", 12);
        buttons.add(List.of(
                new TelegramButtonView("🌐 " + toolLbl + " / conv", CB_LARGE_SESSION),
                new TelegramButtonView("🌐 " + toolLbl + " / proj", CB_LARGE_PROJECT),
                new TelegramButtonView("🌐 " + toolLbl + " / ∞",    CB_LARGE_ALWAYS)
        ));

        TelegramView view = TelegramView.builder()
                .text(text)
                .format(TelegramMessageFormat.PLAIN)
                .buttons(buttons)
                .build();

        sender.sendView(botId, chatId, view);
        log.debug("Telegram ask() envoyé — riskLevel={}, scopeStrict={}, scopeLocal={}",
                request.riskLevel(), request.strictScopeLabel(), request.localScopeLabel());
    }

    /**
     * Formate un label pour un bouton Telegram : extrait le dernier segment (nom de fichier
     * ou répertoire) et tronque si nécessaire.
     *
     * @param value  valeur brute (chemin, commande, nom d'outil…)
     * @param maxLen longueur maximale
     * @return label court adapté à un bouton
     */
    private static String btnLabel(String value, int maxLen) {
        if (value == null) return "?";
        // Pour les chemins : conserver uniquement le dernier segment
        int lastSep = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        String name = lastSep >= 0 ? value.substring(lastSep + 1) : value;
        if (name.isBlank()) name = value; // racine ou drive letter seul
        return name.length() <= maxLen ? name : name.substring(0, maxLen - 1) + "…";
    }

    /**
     * Formate le contexte tâche pour l'inclusion dans le message (ADR-016).
     * Chaque champ sur sa propre ligne pour la lisibilité dans Telegram.
     */
    private static String formatContext(AgentNotification notification) {
        if (notification.ctx() == null) {
            return "";
        }
        return String.format("tâche: %s", notification.ctx().taskId());
    }

    /**
     * Résout le préfixe {@code [NomProjet]} depuis le {@code projectId} du contexte (E2-M5).
     *
     * <p>Si le projet n'est plus trouvable en base (suppression concurrente), affiche
     * l'ID brut plutôt que de planter (dégradation gracieuse). Si le contexte est absent
     * ou le {@code projectRepository} non câblé, retourne une chaîne vide.</p>
     *
     * @param notification la notification à préfixer
     * @return préfixe formaté (ex : {@code "[Mon Projet]"}) ou chaîne vide
     */
    private String resolveProjectPrefix(AgentNotification notification) {
        if (notification.ctx() == null || projectRepository == null) {
            return "";
        }
        String projectId = notification.ctx().projectId();
        if (projectId == null || projectId.isBlank() || "default".equals(projectId)) {
            return "";
        }
        try {
            String projectName = projectRepository.findById(projectId)
                    .map(p -> p.getName())
                    .orElse(projectId); // ID brut si introuvable — dégradation gracieuse
            return "[" + projectName + "]";
        } catch (Exception e) {
            log.debug("Telegram notify() — impossible de résoudre le nom du projet projectId={} : {}",
                    projectId, e.getMessage());
            return "[" + projectId + "]";
        }
    }
}
