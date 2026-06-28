package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationStatus;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gestion du projet actif et de la conversation active par session Telegram.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramSessionService {

    public enum NavigationIntent {
        BROWSE_PROJECTS,
        BROWSE_CONVERSATIONS,
        SWITCH,
        ARCHIVE,
        DELETE
    }

    public record NavigationState(
            NavigationIntent intent,
            String selectedProjectId,
            String selectedConversationId) {
    }

    public enum PendingActionType {
        DELETE_PROJECT,
        DELETE_CONVERSATION,
        ARCHIVE_PROJECT,
        ARCHIVE_CONVERSATION
    }

    public record PendingAction(
            PendingActionType type,
            String targetId,
            String targetLabel) {
    }

    private static final Comparator<ConversationEntity> CONVERSATION_ACTIVITY_COMPARATOR =
            Comparator.comparing(ConversationEntity::getLastMessageAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ConversationEntity::getStartedAt, Comparator.reverseOrder());

    private final ProjectRepository projectRepository;
    private final ConversationRepository conversationRepository;

    /**
     * Sessions actives en memoire : chatId Telegram -> projectId MM.
     */
    private final Map<Long, String> activeSessions = new ConcurrentHashMap<>();

    /**
     * Conversations actives en memoire : chatId Telegram -> conversationId MM.
     */
    private final Map<Long, String> activeConversationIds = new ConcurrentHashMap<>();

    /**
     * Suggestions de switch en attente : chatId Telegram -> liste ordonnee de projectId.
     */
    private final Map<Long, List<String>> switchSuggestions = new ConcurrentHashMap<>();

    /**
     * Suggestions de conversation en attente : chatId Telegram -> liste ordonnee de conversationId.
     */
    private final Map<Long, List<String>> conversationSuggestions = new ConcurrentHashMap<>();

    /**
     * Suggestions de projets pour le menu /conversations : chatId -> liste ordonnee de projectId.
     */
    private final Map<Long, List<String>> conversationProjectSuggestions = new ConcurrentHashMap<>();

    /**
     * Suggestions de suppression de projet : chatId -> liste ordonnee de projectId.
     */
    private final Map<Long, List<String>> deleteProjectSuggestions = new ConcurrentHashMap<>();

    /**
     * Suggestions de suppression de conversation : chatId -> liste ordonnee de conversationId.
     */
    private final Map<Long, List<String>> deleteConversationSuggestions = new ConcurrentHashMap<>();

    /**
     * Suggestions d'archivage de projet : chatId -> liste ordonnee de projectId.
     */
    private final Map<Long, List<String>> archiveProjectSuggestions = new ConcurrentHashMap<>();

    /**
     * Suggestions d'archivage de conversation : chatId -> liste ordonnee de conversationId.
     */
    private final Map<Long, List<String>> archiveConversationSuggestions = new ConcurrentHashMap<>();

    /**
     * Confirmation de suppression en attente.
     */
    private final Map<Long, PendingAction> pendingDeleteConfirmations = new ConcurrentHashMap<>();

    /**
     * Raison d'archivage en attente.
     */
    private final Map<Long, PendingAction> pendingArchiveReasons = new ConcurrentHashMap<>();

    /**
     * Etat de navigation Telegram courant : intention + selection project/conversation.
     */
    private final Map<Long, NavigationState> navigationStates = new ConcurrentHashMap<>();

    /**
     * Suggestions de projets pour les vues Telegram unifiees.
     */
    private final Map<Long, List<String>> navigationProjectSuggestions = new ConcurrentHashMap<>();

    /**
     * Suggestions de conversations pour les vues Telegram unifiees.
     */
    private final Map<Long, List<String>> navigationConversationSuggestions = new ConcurrentHashMap<>();

    /**
     * Retourne le projectId actif pour un chatId, s'il est defini.
     *
     * @param chatId l'identifiant du chat Telegram
     * @return le projectId actif, ou vide si aucune session
     */
    public Optional<String> getActiveProjectId(Long chatId) {
        String projectId = activeSessions.get(chatId);
        log.debug("Session Telegram - chatId={} -> projectId={}", chatId, projectId);
        return Optional.ofNullable(projectId);
    }

    /**
     * Resout le projectId actif pour un chatId avec repli sur le premier projet ACTIVE.
     *
     * @param chatId l'identifiant du chat Telegram
     * @return le projectId resolu, ou vide si aucun projet n'est disponible
     */
    public Optional<String> resolveProjectId(Long chatId) {
        Optional<String> stored = getActiveProjectId(chatId);
        if (stored.isPresent()) {
            String projectId = stored.get();
            boolean stillActive = projectRepository.findById(projectId)
                    .map(project -> project.getStatus() == ProjectStatus.ACTIVE)
                    .orElse(false);
            if (stillActive) {
                return stored;
            }
            activeSessions.remove(chatId);
            clearActiveConversationId(chatId);
            clearTransientState(chatId);
            log.info("Session Telegram invalidee - chatId={}, projectId={} n'est plus ACTIVE",
                    chatId, projectId);
        }

        List<ProjectEntity> activeProjects = projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
        if (!activeProjects.isEmpty()) {
            String fallbackId = activeProjects.getFirst().getId();
            log.debug("Session Telegram - chatId={} sans session active, repli sur projectId={}",
                    chatId, fallbackId);
            return Optional.of(fallbackId);
        }
        return Optional.empty();
    }

    /**
     * Definit le projet actif pour un chatId.
     *
     * @param chatId l'identifiant du chat Telegram
     * @param projectId le projectId a activer
     * @param projectName le nom du projet pour le log
     */
    public void setActiveProject(Long chatId, String projectId, String projectName) {
        activeSessions.put(chatId, projectId);
        clearActiveConversationId(chatId);
        clearTransientState(chatId);
        log.info("Switch de projet actif Telegram - chatId={}, projectId={}, nom='{}'",
                chatId, projectId, projectName);
    }

    /**
     * Efface le projet actif d'un chat et tout son etat derive.
     *
     * @param chatId identifiant du chat Telegram
     */
    public void clearActiveProject(Long chatId) {
        String removed = activeSessions.remove(chatId);
        clearActiveConversationId(chatId);
        clearTransientState(chatId);
        if (removed != null) {
            log.info("Projet actif Telegram reinitialise - chatId={}, projectId={}", chatId, removed);
        } else {
            log.debug("Projet actif Telegram deja absent - chatId={}", chatId);
        }
    }

    /**
     * Retourne la conversation active pour un chatId, si elle est definie.
     *
     * @param chatId l'identifiant du chat Telegram
     * @return l'ID de conversation active, ou vide
     */
    public Optional<String> getActiveConversationId(Long chatId) {
        String conversationId = activeConversationIds.get(chatId);
        log.debug("Session Telegram - chatId={} -> conversationId={}", chatId, conversationId);
        return Optional.ofNullable(conversationId);
    }

    /**
     * Definit la conversation active pour un chatId.
     *
     * @param chatId l'identifiant du chat Telegram
     * @param conversationId l'ID de conversation a associer
     */
    public void setActiveConversationId(Long chatId, String conversationId) {
        activeConversationIds.put(chatId, conversationId);
        log.info("Conversation active Telegram definie - chatId={}, conversationId={}",
                chatId, conversationId);
    }

    /**
     * Supprime la conversation active d'un chatId.
     *
     * @param chatId l'identifiant du chat Telegram
     */
    public void clearActiveConversationId(Long chatId) {
        String removed = activeConversationIds.remove(chatId);
        if (removed != null) {
            log.info("Conversation active Telegram reinitialisee - chatId={}, conversationId={}",
                    chatId, removed);
        } else {
            log.debug("Conversation active Telegram deja absente - chatId={}", chatId);
        }
    }

    /**
     * Recherche un projet ACTIVE par son nom ou son slug.
     *
     * @param nameOrSlug le nom ou slug du projet
     * @return le projet correspondant, ou vide
     */
    public Optional<ProjectEntity> findActiveProjectByName(String nameOrSlug) {
        List<ProjectEntity> activeProjects = projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
        Optional<ProjectEntity> byName = activeProjects.stream()
                .filter(project -> project.getName().equalsIgnoreCase(nameOrSlug))
                .findFirst();
        if (byName.isPresent()) {
            return byName;
        }
        return activeProjects.stream()
                .filter(project -> project.getSanitizedName().equalsIgnoreCase(nameOrSlug))
                .findFirst();
    }

    /**
     * Recherche des projets actifs approchants.
     *
     * @param query texte saisi
     * @param limit nombre maximal de resultats
     * @return liste ordonnee des projets approchants
     */
    public List<ProjectEntity> findActiveProjectsByQuery(String query, int limit) {
        String normalized = normalizeQuery(query);
        List<ProjectEntity> activeProjects = projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
        if (normalized.isBlank()) {
            return activeProjects.stream()
                    .sorted(Comparator.comparing(ProjectEntity::getName, String.CASE_INSENSITIVE_ORDER))
                    .limit(limit)
                    .toList();
        }

        return activeProjects.stream()
                .filter(project -> computeMatchRank(project, normalized) < Integer.MAX_VALUE)
                .sorted(Comparator
                        .comparingInt((ProjectEntity project) -> computeMatchRank(project, normalized))
                        .thenComparing(ProjectEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    /**
     * Retourne tous les projets ACTIVE.
     *
     * @return liste des projets actifs
     */
    public List<ProjectEntity> listActiveProjects() {
        return projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
    }

    /**
     * Retourne les projets actifs tries par activite la plus recente.
     *
     * <p>La priorite est donnee au dernier message/conversation connu. En
     * fallback, on trie sur {@code updatedAt}, puis sur le nom.</p>
     *
     * @return liste triee des projets actifs
     */
    public List<ProjectEntity> listActiveProjectsByRecentActivity() {
        List<ProjectEntity> activeProjects = projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
        if (activeProjects.isEmpty()) {
            return List.of();
        }
        List<String> projectIds = activeProjects.stream().map(ProjectEntity::getId).toList();
        Map<String, String> activityByProject = conversationRepository.findLastActivityByProjectIds(projectIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row[0],
                        row -> (String) row[1]));
        return activeProjects.stream()
                .sorted(Comparator
                        .comparing((ProjectEntity project) ->
                                        parseInstant(activityByProject.get(project.getId())),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(project -> parseInstant(project.getUpdatedAt()),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProjectEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Retourne le nombre de conversations OPEN par projet en une seule requete.
     *
     * @param projectIds liste des IDs de projets
     * @return map projectId -> nombre de conversations ouvertes
     */
    public Map<String, Long> countOpenConversationsByProjects(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = conversationRepository.countOpenByProjectIds(projectIds, ConversationStatus.OPEN);
        return ConversationRepository.toOpenCountMap(rows);
    }

    /**
     * Compte les conversations OPEN d'un projet.
     *
     * @param projectId l'ID du projet
     * @return nombre de conversations ouvertes
     */
    public long countOpenConversations(String projectId) {
        return conversationRepository.countByProjectIdAndStatus(projectId, ConversationStatus.OPEN);
    }

    /**
     * Liste les conversations ouvertes d'un projet triees par activite recente.
     *
     * @param projectId l'ID du projet
     * @param limit nombre maximal de resultats
     * @return conversations ouvertes triees
     */
    public List<ConversationEntity> listOpenConversationsForProject(String projectId, int limit) {
        return conversationRepository.findAllByProjectIdAndStatus(projectId, ConversationStatus.OPEN).stream()
                .sorted(CONVERSATION_ACTIVITY_COMPARATOR)
                .limit(limit)
                .toList();
    }

    /**
     * Enregistre une liste ordonnee de suggestions de switch pour un chat.
     *
     * @param chatId identifiant du chat Telegram
     * @param projectIds IDs de projets proposes
     */
    public void setSwitchSuggestions(Long chatId, List<String> projectIds) {
        switchSuggestions.put(chatId, List.copyOf(projectIds));
        log.debug("Suggestions de switch enregistrees - chatId={}, count={}", chatId, projectIds.size());
    }

    /**
     * Efface les suggestions de switch d'un chat.
     *
     * @param chatId identifiant du chat Telegram
     */
    public void clearSwitchSuggestions(Long chatId) {
        switchSuggestions.remove(chatId);
        log.debug("Suggestions de switch effacees - chatId={}", chatId);
    }

    /**
     * Resout un projet depuis la liste de suggestions de switch.
     *
     * @param chatId identifiant du chat Telegram
     * @param index index du bouton clique
     * @return projet resolu si toujours ACTIVE
     */
    public Optional<ProjectEntity> resolveSwitchSuggestion(Long chatId, int index) {
        List<String> suggestions = switchSuggestions.get(chatId);
        if (suggestions == null || index < 0 || index >= suggestions.size()) {
            log.debug("Suggestion de switch introuvable - chatId={}, index={}", chatId, index);
            return Optional.empty();
        }
        String projectId = suggestions.get(index);
        return projectRepository.findById(projectId)
                .filter(project -> project.getStatus() == ProjectStatus.ACTIVE);
    }

    /**
     * Enregistre une liste ordonnee de suggestions de conversation pour un chat.
     *
     * @param chatId identifiant du chat Telegram
     * @param conversationIds IDs de conversations proposees
     */
    public void setConversationSuggestions(Long chatId, List<String> conversationIds) {
        conversationSuggestions.put(chatId, List.copyOf(conversationIds));
        log.debug("Suggestions de conversation enregistrees - chatId={}, count={}",
                chatId, conversationIds.size());
    }

    /**
     * Efface les suggestions de conversation en attente.
     *
     * @param chatId identifiant du chat Telegram
     */
    public void clearConversationSuggestions(Long chatId) {
        conversationSuggestions.remove(chatId);
        log.debug("Suggestions de conversation effacees - chatId={}", chatId);
    }

    public void setConversationProjectSuggestions(Long chatId, List<String> projectIds) {
        conversationProjectSuggestions.put(chatId, List.copyOf(projectIds));
        log.debug("Suggestions de projets conversation enregistrees - chatId={}, count={}",
                chatId, projectIds.size());
    }

    public Optional<ProjectEntity> resolveConversationProjectSuggestion(Long chatId, int index) {
        return resolveProjectSuggestion(conversationProjectSuggestions, chatId, index)
                .filter(project -> project.getStatus() == ProjectStatus.ACTIVE);
    }

    public void clearConversationProjectSuggestions(Long chatId) {
        conversationProjectSuggestions.remove(chatId);
        log.debug("Suggestions de projets conversation effacees - chatId={}", chatId);
    }

    /**
     * Resout une conversation depuis sa suggestion indexee.
     *
     * @param chatId identifiant du chat Telegram
     * @param index index 0-based dans la liste affichee
     * @return conversation resolue si toujours OPEN
     */
    public Optional<ConversationEntity> resolveConversationSuggestion(Long chatId, int index) {
        List<String> suggestions = conversationSuggestions.get(chatId);
        if (suggestions == null || index < 0 || index >= suggestions.size()) {
            log.debug("Suggestion de conversation introuvable - chatId={}, index={}", chatId, index);
            return Optional.empty();
        }
        String conversationId = suggestions.get(index);
        return conversationRepository.findById(conversationId)
                .filter(conversation -> conversation.getStatus() == ConversationStatus.OPEN);
    }

    public void setDeleteProjectSuggestions(Long chatId, List<String> projectIds) {
        deleteProjectSuggestions.put(chatId, List.copyOf(projectIds));
        log.debug("Suggestions delete project enregistrees - chatId={}, count={}", chatId, projectIds.size());
    }

    public Optional<ProjectEntity> resolveDeleteProjectSuggestion(Long chatId, int index) {
        return resolveProjectSuggestion(deleteProjectSuggestions, chatId, index);
    }

    public void clearDeleteProjectSuggestions(Long chatId) {
        deleteProjectSuggestions.remove(chatId);
        log.debug("Suggestions delete project effacees - chatId={}", chatId);
    }

    public void setDeleteConversationSuggestions(Long chatId, List<String> conversationIds) {
        deleteConversationSuggestions.put(chatId, List.copyOf(conversationIds));
        log.debug("Suggestions delete conversation enregistrees - chatId={}, count={}",
                chatId, conversationIds.size());
    }

    public Optional<ConversationEntity> resolveDeleteConversationSuggestion(Long chatId, int index) {
        return resolveConversationSuggestion(deleteConversationSuggestions, chatId, index, false);
    }

    public void clearDeleteConversationSuggestions(Long chatId) {
        deleteConversationSuggestions.remove(chatId);
        log.debug("Suggestions delete conversation effacees - chatId={}", chatId);
    }

    public void setArchiveProjectSuggestions(Long chatId, List<String> projectIds) {
        archiveProjectSuggestions.put(chatId, List.copyOf(projectIds));
        log.debug("Suggestions archive project enregistrees - chatId={}, count={}", chatId, projectIds.size());
    }

    public Optional<ProjectEntity> resolveArchiveProjectSuggestion(Long chatId, int index) {
        Optional<ProjectEntity> project = resolveProjectSuggestion(archiveProjectSuggestions, chatId, index);
        return project.filter(p -> p.getStatus() == ProjectStatus.ACTIVE);
    }

    public void clearArchiveProjectSuggestions(Long chatId) {
        archiveProjectSuggestions.remove(chatId);
        log.debug("Suggestions archive project effacees - chatId={}", chatId);
    }

    public void setArchiveConversationSuggestions(Long chatId, List<String> conversationIds) {
        archiveConversationSuggestions.put(chatId, List.copyOf(conversationIds));
        log.debug("Suggestions archive conversation enregistrees - chatId={}, count={}",
                chatId, conversationIds.size());
    }

    public Optional<ConversationEntity> resolveArchiveConversationSuggestion(Long chatId, int index) {
        return resolveConversationSuggestion(archiveConversationSuggestions, chatId, index, true);
    }

    public void clearArchiveConversationSuggestions(Long chatId) {
        archiveConversationSuggestions.remove(chatId);
        log.debug("Suggestions archive conversation effacees - chatId={}", chatId);
    }

    public void setPendingDeleteConfirmation(Long chatId, PendingAction action) {
        pendingDeleteConfirmations.put(chatId, action);
        log.debug("Confirmation delete en attente - chatId={}, type={}, targetId={}",
                chatId, action.type(), action.targetId());
    }

    public Optional<PendingAction> getPendingDeleteConfirmation(Long chatId) {
        return Optional.ofNullable(pendingDeleteConfirmations.get(chatId));
    }

    public void clearPendingDeleteConfirmation(Long chatId) {
        pendingDeleteConfirmations.remove(chatId);
        log.debug("Confirmation delete effacee - chatId={}", chatId);
    }

    public void setPendingArchiveReason(Long chatId, PendingAction action) {
        pendingArchiveReasons.put(chatId, action);
        log.debug("Raison archive en attente - chatId={}, type={}, targetId={}",
                chatId, action.type(), action.targetId());
    }

    public Optional<PendingAction> getPendingArchiveReason(Long chatId) {
        return Optional.ofNullable(pendingArchiveReasons.get(chatId));
    }

    public void clearPendingArchiveReason(Long chatId) {
        pendingArchiveReasons.remove(chatId);
        log.debug("Raison archive effacee - chatId={}", chatId);
    }

    public void clearTransientState(Long chatId) {
        clearSwitchSuggestions(chatId);
        clearConversationSuggestions(chatId);
        clearConversationProjectSuggestions(chatId);
        clearDeleteProjectSuggestions(chatId);
        clearDeleteConversationSuggestions(chatId);
        clearArchiveProjectSuggestions(chatId);
        clearArchiveConversationSuggestions(chatId);
        clearPendingDeleteConfirmation(chatId);
        clearPendingArchiveReason(chatId);
        clearNavigationState(chatId);
    }

    public void openNavigation(Long chatId, NavigationIntent intent, List<String> projectIds) {
        navigationStates.put(chatId, new NavigationState(intent, null, null));
        navigationProjectSuggestions.put(chatId, List.copyOf(projectIds));
        navigationConversationSuggestions.remove(chatId);
        log.debug("Navigation Telegram ouverte - chatId={}, intent={}, projectCount={}",
                chatId, intent, projectIds.size());
    }

    public Optional<NavigationState> getNavigationState(Long chatId) {
        return Optional.ofNullable(navigationStates.get(chatId));
    }

    public void selectNavigationProject(Long chatId, String projectId, List<String> conversationIds) {
        NavigationIntent intent = getNavigationState(chatId)
                .map(NavigationState::intent)
                .orElse(NavigationIntent.BROWSE_PROJECTS);
        navigationStates.put(chatId, new NavigationState(intent, projectId, null));
        navigationConversationSuggestions.put(chatId, List.copyOf(conversationIds));
        log.debug("Projet de navigation selectionne - chatId={}, intent={}, projectId={}, conversationCount={}",
                chatId, intent, projectId, conversationIds.size());
    }

    public void selectNavigationConversation(Long chatId, String conversationId) {
        NavigationState previous = getNavigationState(chatId)
                .orElse(new NavigationState(NavigationIntent.BROWSE_PROJECTS, null, null));
        navigationStates.put(chatId, new NavigationState(
                previous.intent(),
                previous.selectedProjectId(),
                conversationId));
        log.debug("Conversation de navigation selectionnee - chatId={}, intent={}, conversationId={}",
                chatId, previous.intent(), conversationId);
    }

    public Optional<ProjectEntity> resolveNavigationProjectSuggestion(Long chatId, int index) {
        return resolveProjectSuggestion(navigationProjectSuggestions, chatId, index)
                .filter(project -> project.getStatus() == ProjectStatus.ACTIVE);
    }

    public Optional<ConversationEntity> resolveNavigationConversationSuggestion(Long chatId, int index) {
        return resolveConversationSuggestion(navigationConversationSuggestions, chatId, index, true);
    }

    public void clearNavigationState(Long chatId) {
        navigationStates.remove(chatId);
        navigationProjectSuggestions.remove(chatId);
        navigationConversationSuggestions.remove(chatId);
        log.debug("Navigation Telegram effacee - chatId={}", chatId);
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.strip().toLowerCase();
    }

    private Optional<ProjectEntity> resolveProjectSuggestion(
            Map<Long, List<String>> suggestionsByChat,
            Long chatId,
            int index) {
        List<String> suggestions = suggestionsByChat.get(chatId);
        if (suggestions == null || index < 0 || index >= suggestions.size()) {
            log.debug("Suggestion projet introuvable - chatId={}, index={}", chatId, index);
            return Optional.empty();
        }
        return projectRepository.findById(suggestions.get(index));
    }

    private Optional<ConversationEntity> resolveConversationSuggestion(
            Map<Long, List<String>> suggestionsByChat,
            Long chatId,
            int index,
            boolean requireOpen) {
        List<String> suggestions = suggestionsByChat.get(chatId);
        if (suggestions == null || index < 0 || index >= suggestions.size()) {
            log.debug("Suggestion conversation introuvable - chatId={}, index={}", chatId, index);
            return Optional.empty();
        }
        Optional<ConversationEntity> conversation = conversationRepository.findById(suggestions.get(index));
        if (!requireOpen) {
            return conversation;
        }
        return conversation.filter(c -> c.getStatus() == ConversationStatus.OPEN);
    }

    private int computeMatchRank(ProjectEntity project, String normalizedQuery) {
        String name = project.getName() == null ? "" : project.getName().toLowerCase();
        String slug = project.getSanitizedName() == null ? "" : project.getSanitizedName().toLowerCase();
        if (name.equals(normalizedQuery) || slug.equals(normalizedQuery)) {
            return 0;
        }
        if (name.startsWith(normalizedQuery) || slug.startsWith(normalizedQuery)) {
            return 1;
        }
        if (name.contains(normalizedQuery) || slug.contains(normalizedQuery)) {
            return 2;
        }
        return Integer.MAX_VALUE;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            log.debug("Instant projet/conversation illisible - value='{}'", value, ex);
            return null;
        }
    }
}
