package fr.ses10doigts.mm.app.telegram;

import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationStatus;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
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
            log.info("Session Telegram invalidée — chatId={}, projectId={} n'est plus ACTIVE",
                    chatId, projectId);
        }
        // Repli : premier projet ACTIVE
        List<ProjectEntity> activeProjects = projectRepository.findAllByStatus(ProjectStatus.ACTIVE);
        if (!activeProjects.isEmpty()) {
            String fallbackId = activeProjects.get(0).getId();
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
        log.info("Switch de projet actif Telegram — chatId={}, projectId={}, nom='{}'",
                chatId, projectId, projectName);
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
}
