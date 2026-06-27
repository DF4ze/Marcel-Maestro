package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationStatus;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gestion du projet actif par session Telegram (E2-M5).
 *
 * <p>Stockage in-memory : {@code Map<chatId, projectId>}. Ce choix intentionnel
 * (YAGNI) est documenté dans les points ouverts de la conception §8 : la perte
 * de la session au redémarrage est acceptable pour un seul utilisateur. Migration
 * vers une table {@code telegram_session} si le besoin se confirme.</p>
 *
 * <p>Utilisé par {@link TelegramMmController} pour :</p>
 * <ul>
 *   <li>Retrouver le projet actif d'un chatId avant de créer une conversation.</li>
 *   <li>Changer de projet actif via {@code /switch <name>}.</li>
 *   <li>Lister les projets actifs via {@code /projects}.</li>
 * </ul>
 *
 * <p>Logging (coding rules) :</p>
 * <ul>
 *   <li>{@code log.info} : switch de projet actif.</li>
 *   <li>{@code log.debug} : chatId → projectId résolu.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramSessionService {

    private final ProjectRepository projectRepository;
    private final ConversationRepository conversationRepository;

    /**
     * Sessions actives en mémoire : chatId Telegram → projectId MM.
     * {@link ConcurrentHashMap} pour la sécurité thread (virtual threads).
     */
    private final Map<Long, String> activeSessions = new ConcurrentHashMap<>();

    /**
     * Conversations actives en mémoire : chatId Telegram → conversationId MM.
     * {@link ConcurrentHashMap} pour la sécurité thread (virtual threads).
     */
    private final Map<Long, String> activeConversationIds = new ConcurrentHashMap<>();

    /**
     * Suggestions de switch en attente : chatId Telegram → liste ordonnée de projectId.
     */
    private final Map<Long, List<String>> switchSuggestions = new ConcurrentHashMap<>();

    // ── Lecture ──────────────────────────────────────────────────────────────

    /**
     * Retourne le projectId actif pour un chatId, s'il est défini.
     *
     * @param chatId l'identifiant du chat Telegram
     * @return le projectId actif, ou {@link Optional#empty()} si aucune session
     */
    public Optional<String> getActiveProjectId(Long chatId) {
        String projectId = activeSessions.get(chatId);
        log.debug("Session Telegram — chatId={} → projectId={}", chatId, projectId);
        return Optional.ofNullable(projectId);
    }

    /**
     * Résout le projectId actif pour un chatId.
     *
     * <p>Stratégie :</p>
     * <ol>
     *   <li>Session en mémoire — si le projet est toujours {@code ACTIVE} en base.
     *       Si le projet a été archivé ou supprimé depuis le dernier {@code /switch},
     *       la session est purgée et on passe au repli.</li>
     *   <li>Repli : premier projet {@code ACTIVE} disponible en base.</li>
     *   <li>{@link Optional#empty()} si aucun projet ACTIVE n'existe.</li>
     * </ol>
     *
     * @param chatId l'identifiant du chat Telegram
     * @return le projectId résolu, ou {@link Optional#empty()} si aucun projet disponible
     */
    public Optional<String> resolveProjectId(Long chatId) {
        Optional<String> stored = getActiveProjectId(chatId);
        if (stored.isPresent()) {
            String projectId = stored.get();
            boolean stillActive = projectRepository.findById(projectId)
                    .map(p -> p.getStatus() == ProjectStatus.ACTIVE)
                    .orElse(false);
            if (stillActive) {
                return stored;
            }
            // Projet archivé ou supprimé depuis la mise en session — invalider
            activeSessions.remove(chatId);
            clearActiveConversationId(chatId);
            clearSwitchSuggestions(chatId);
            log.info("Session Telegram invalidée — chatId={}, projectId={} n'est plus ACTIVE",
                    chatId, projectId);
        }
        // Repli : premier projet ACTIVE
        List<ProjectEntity> activeProjects = projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
        if (!activeProjects.isEmpty()) {
            String fallbackId = activeProjects.getFirst().getId();
            log.debug("Session Telegram — chatId={} sans session active, repli sur projectId={}",
                    chatId, fallbackId);
            return Optional.of(fallbackId);
        }
        return Optional.empty();
    }

    // ── Écriture ─────────────────────────────────────────────────────────────

    /**
     * Définit le projet actif pour un chatId.
     *
     * @param chatId    l'identifiant du chat Telegram
     * @param projectId le projectId à activer
     * @param projectName le nom du projet (pour le log)
     */
    public void setActiveProject(Long chatId, String projectId, String projectName) {
        activeSessions.put(chatId, projectId);
        clearActiveConversationId(chatId);
        clearSwitchSuggestions(chatId);
        log.info("Switch de projet actif Telegram — chatId={}, projectId={}, nom='{}'",
                chatId, projectId, projectName);
    }

    /**
     * Retourne la conversation active pour un chatId, si elle est définie.
     *
     * @param chatId l'identifiant du chat Telegram
     * @return l'ID de conversation active, ou {@link Optional#empty()} si aucune n'est définie
     */
    public Optional<String> getActiveConversationId(Long chatId) {
        String conversationId = activeConversationIds.get(chatId);
        log.debug("Session Telegram — chatId={} → conversationId={}", chatId, conversationId);
        return Optional.ofNullable(conversationId);
    }

    /**
     * Définit la conversation active pour un chatId.
     *
     * @param chatId         l'identifiant du chat Telegram
     * @param conversationId l'ID de conversation à associer
     */
    public void setActiveConversationId(Long chatId, String conversationId) {
        activeConversationIds.put(chatId, conversationId);
        log.info("Conversation active Telegram définie — chatId={}, conversationId={}",
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
            log.info("Conversation active Telegram réinitialisée — chatId={}, conversationId={}",
                    chatId, removed);
        } else {
            log.debug("Conversation active Telegram déjà absente — chatId={}", chatId);
        }
    }

    // ── Recherche ────────────────────────────────────────────────────────────

    /**
     * Recherche un projet ACTIVE par son nom (insensible à la casse) ou son slug sanitisé.
     *
     * <p>Priorité : correspondance exacte sur {@code name} (insensible à la casse),
     * puis correspondance sur {@code sanitizedName}. Si plusieurs projets correspondent
     * (impossible car {@code sanitizedName} est UNIQUE, mais {@code name} ne l'est pas),
     * le premier résultat est retourné.</p>
     *
     * @param nameOrSlug le nom ou slug du projet à rechercher
     * @return le projet correspondant, ou {@link Optional#empty()} s'il n'existe pas
     */
    public Optional<ProjectEntity> findActiveProjectByName(String nameOrSlug) {
        List<ProjectEntity> activeProjects = projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
        // Correspondance exacte sur name (insensible à la casse)
        Optional<ProjectEntity> byName = activeProjects.stream()
                .filter(p -> p.getName().equalsIgnoreCase(nameOrSlug))
                .findFirst();
        if (byName.isPresent()) {
            return byName;
        }
        // Correspondance sur sanitizedName
        return activeProjects.stream()
                .filter(p -> p.getSanitizedName().equalsIgnoreCase(nameOrSlug))
                .findFirst();
    }

    /**
     * Recherche des projets actifs approchants à partir d'un texte libre.
     *
     * <p>Ordre de priorité :</p>
     * <ol>
     *   <li>égalité exacte sur {@code name} ou {@code sanitizedName}</li>
     *   <li>préfixe sur {@code name} ou {@code sanitizedName}</li>
     *   <li>contenance sur {@code name} ou {@code sanitizedName}</li>
     * </ol>
     *
     * @param query texte saisi par l'utilisateur ; vide = tous les projets actifs
     * @param limit nombre maximal de résultats
     * @return liste ordonnée des projets approchants
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

    // ── Données pour /projects ────────────────────────────────────────────────

    /**
     * Retourne tous les projets {@code ACTIVE}.
     *
     * @return liste des projets actifs (peut être vide)
     */
    public List<ProjectEntity> listActiveProjects() {
        return projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
    }

    /**
     * Retourne en une seule requête le nombre de conversations {@code OPEN} par projet
     * (anti N+1, E2-M5).
     *
     * <p>Utilisé par {@code /projects} dans {@link TelegramMmController} pour éviter
     * une requête SQL par projet. Les projets sans conversation ouverte n'apparaissent
     * pas dans la map (valeur absente = 0).</p>
     *
     * @param projectIds liste des IDs à agréger
     * @return map projectId → nombre de conversations ouvertes (absent = 0)
     */
    public Map<String, Long> countOpenConversationsByProjects(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = conversationRepository.countOpenByProjectIds(projectIds, ConversationStatus.OPEN);
        return ConversationRepository.toOpenCountMap(rows);
    }

    /**
     * Compte les conversations {@code OPEN} d'un seul projet.
     *
     * <p>Pour un usage isolé (ex : après un {@code /switch}). Préférer
     * {@link #countOpenConversationsByProjects} pour les agrégations multi-projets.</p>
     *
     * @param projectId l'ID du projet
     * @return nombre de conversations ouvertes
     */
    public long countOpenConversations(String projectId) {
        return conversationRepository.countByProjectIdAndStatus(projectId, ConversationStatus.OPEN);
    }

    /**
     * Enregistre une liste ordonnée de suggestions de switch pour un chat.
     *
     * @param chatId identifiant du chat Telegram
     * @param projectIds IDs de projets proposés dans l'ordre d'affichage
     */
    public void setSwitchSuggestions(Long chatId, List<String> projectIds) {
        switchSuggestions.put(chatId, List.copyOf(projectIds));
        log.debug("Suggestions de switch enregistrées — chatId={}, count={}", chatId, projectIds.size());
    }

    /**
     * Supprime les suggestions de switch en attente pour un chat.
     *
     * @param chatId identifiant du chat Telegram
     */
    public void clearSwitchSuggestions(Long chatId) {
        switchSuggestions.remove(chatId);
        log.debug("Suggestions de switch effacées — chatId={}", chatId);
    }

    /**
     * Résout un projet à partir de son index dans la liste de suggestions d'un chat.
     *
     * @param chatId identifiant du chat Telegram
     * @param index index du bouton cliqué
     * @return projet résolu si l'index est valide et que le projet existe toujours
     */
    public Optional<ProjectEntity> resolveSwitchSuggestion(Long chatId, int index) {
        List<String> suggestions = switchSuggestions.get(chatId);
        if (suggestions == null || index < 0 || index >= suggestions.size()) {
            log.debug("Suggestion de switch introuvable — chatId={}, index={}", chatId, index);
            return Optional.empty();
        }
        String projectId = suggestions.get(index);
        return projectRepository.findById(projectId)
                .filter(project -> project.getStatus() == ProjectStatus.ACTIVE);
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.strip().toLowerCase();
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
}
