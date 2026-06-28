package fr.ses10doigts.mm.app.project;

import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationStatus;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceEntity;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service applicatif de gestion du cycle de vie des projets Marcel Maestro (E2-M1).
 *
 * <p>Orchestre la persistance JPA (via {@link ProjectRepository} et
 * {@link ProjectWorkspaceRepository}) et les opérations filesystem (création,
 * suppression de dossiers). La DB est la source de vérité (ADR-022) ;
 * le filesystem en est une projection.</p>
 *
 * <p>Règles de sanitisation : le nom saisi est converti en slug kebab-case
 * ASCII minuscule (ex : "Mon Super Projet!" → "mon-super-projet"). Si ce slug
 * est déjà pris, une {@link ProjectNameConflictException} est levée avant toute
 * opération filesystem.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    public static final String DEFAULT_MISC_PROJECT_NAME = "Autre";
    public static final String DEFAULT_MISC_PROJECT_SLUG = "autre";
    private static final String PROJECT_FILE_NAME = "PROJECT.md";
    private static final String ROADMAP_FILE_NAME = "ROADMAP.md";
    private static final String PROJECT_FILE_TEMPLATE = """
            # PROJECT

            Ce fichier est initialisé automatiquement à la création du projet.

            Marcel :
            - pose des questions ciblées à l'utilisateur pour comprendre le projet ;
            - clarifie le besoin métier, le périmètre, la stack, les contraintes et les règles de travail ;
            - complète ensuite ce document progressivement avec des informations factuelles validées.

            Questions à poser en priorité :
            1. Quel est l'objectif principal du projet ?
            2. Qui sont les utilisateurs ou systèmes concernés ?
            3. Quelle stack technique et quelles versions faut-il utiliser ?
            4. Quelles contraintes d'architecture, de sécurité, de performance ou de déploiement faut-il respecter ?
            5. Quelles règles métier, conventions de code ou décisions d'architecture sont déjà actées ?

            Sections à compléter avec l'utilisateur :
            - Objectif
            - Contexte métier
            - Stack technique
            - Contraintes
            - Décisions actées
            - Questions ouvertes

            <!-- MARCEL:PROJECT_BOOTSTRAP_PENDING -->

            ## Notes collectées pendant le cadrage

            <!-- MARCEL:PROJECT_BOOTSTRAP_NOTES -->
            """;
    private static final String ROADMAP_FILE_TEMPLATE = """
            # ROADMAP

            Ce fichier est initialisé automatiquement à la création du projet.

            Marcel :
            - pose des questions ciblées à l'utilisateur pour construire un plan d'exécution réaliste ;
            - clarifie les priorités, livrables, dépendances, risques et critères de validation ;
            - met ensuite à jour cette roadmap au fil du projet.

            Questions à poser en priorité :
            1. Quel est le premier résultat concret attendu ?
            2. Quelles étapes ou milestones sont déjà connues ?
            3. Quelles dépendances, validations humaines ou contraintes externes peuvent bloquer l'avancement ?
            4. Quels tests ou critères de succès permettent de valider chaque étape ?
            5. Y a-t-il une échéance, un ordre de priorité ou un niveau d'urgence particulier ?

            Sections à compléter avec l'utilisateur :
            - Vision de livraison
            - Milestones
            - Tâches immédiates
            - Risques et dépendances
            - Critères de succès
            - Prochaines questions
            """;

    private static final String DEFAULT_MISC_PROJECT_TEMPLATE = """
            # PROJECT

            Ce projet systeme s'appelle "Autre".

            Il sert de projet fourre-tout pour les demandes sans rapport entre elles :
            - questions ponctuelles ;
            - tests rapides ;
            - demandes diverses qui ne meritent pas un projet dedie ;
            - conversations heterogenes sans continuite fonctionnelle.

            Regles d'usage :
            - ne suppose pas qu'une question soit liee aux precedentes ;
            - traite chaque demande comme potentiellement independante ;
            - propose de creer ou basculer vers un vrai projet dedie si un sujet devient suivi, structure ou durable ;
            - ce projet reste le projet par defaut de Marcel et ne doit pas etre archive ni supprime.
            """;

    private final ProjectRepository projectRepository;
    private final ProjectWorkspaceRepository workspaceRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMemory chatMemory;
    private final WorkspaceProperties workspaceProperties;

    // ─────────────────────────────────────────────────────────────────────────
    // Création / import
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Crée un nouveau projet : sanitise le nom, vérifie l'unicité du slug,
     * crée le dossier dans le workspace, puis insère en DB avec statut ACTIVE.
     *
     * @param name le nom d'affichage choisi par l'utilisateur (non vide)
     * @return l'entité créée et persistée
     * @throws ProjectNameConflictException si le slug calculé est déjà utilisé
     * @throws UncheckedIOException         si la création du dossier échoue
     */
    @Transactional
    public ProjectEntity create(String name) {
        String slug = sanitize(name);
        log.debug("Sanitisation du nom de projet — input='{}', slug='{}'", name, slug);

        checkSlugUniqueness(slug);

        Path workspacePath = resolveWorkspacePath(slug);
        log.debug("Chemin workspace calculé — path='{}'", workspacePath);

        createDirectory(workspacePath);
        initializeProjectContextFiles(workspacePath, slug);

        Instant now = Instant.now();
        ProjectEntity project = ProjectEntity.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .sanitizedName(slug)
                .workspacePath(workspacePath.toAbsolutePath().toString())
                .status(ProjectStatus.ACTIVE)
                .createdAt(now.toString())
                .updatedAt(now.toString())
                .build();

        ProjectEntity saved = projectRepository.save(project);
        log.info("Projet créé — id={}, name='{}', sanitizedName='{}', workspacePath='{}'",
                saved.getId(), saved.getName(), saved.getSanitizedName(), saved.getWorkspacePath());
        return saved;
    }

    /**
     * Importe un dossier existant en tant que projet sans recréer le dossier.
     *
     * <p>Utile quand un dossier de travail préexiste et que l'utilisateur
     * veut l'enregistrer dans Marcel sans risque d'écrasement.</p>
     *
     * @param name le nom d'affichage du projet
     * @param path le chemin absolu du dossier existant
     * @return l'entité créée et persistée
     * @throws ProjectNameConflictException  si le slug est déjà utilisé
     * @throws IllegalArgumentException      si le chemin n'existe pas ou n'est pas un dossier
     */
    @Transactional
    public ProjectEntity importExisting(String name, String path) {
        String slug = sanitize(name);
        log.debug("Import de projet existant — input='{}', slug='{}', path='{}'", name, slug, path);

        checkSlugUniqueness(slug);

        Path dir = Paths.get(path);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException(
                    "Le chemin fourni n'existe pas ou n'est pas un dossier : " + path);
        }

        Instant now = Instant.now();
        ProjectEntity project = ProjectEntity.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .sanitizedName(slug)
                .workspacePath(dir.toAbsolutePath().toString())
                .status(ProjectStatus.ACTIVE)
                .createdAt(now.toString())
                .updatedAt(now.toString())
                .build();

        ProjectEntity saved = projectRepository.save(project);
        log.info("Projet importé — id={}, name='{}', sanitizedName='{}', path='{}'",
                saved.getId(), saved.getName(), saved.getSanitizedName(), saved.getWorkspacePath());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Archivage / désarchivage
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Garantit la presence du projet systeme par defaut "Autre".
     *
     * @return le projet systeme "Autre"
     */
    @Transactional
    public ProjectEntity ensureDefaultMiscProjectExists() {
        return projectRepository.findBySanitizedName(DEFAULT_MISC_PROJECT_SLUG)
                .map(existing -> {
                    Path workspacePath = Paths.get(existing.getWorkspacePath());
                    createDirectory(workspacePath);
                    initializeProjectContextFiles(workspacePath, existing.getSanitizedName());
                    if (existing.getStatus() != ProjectStatus.ACTIVE) {
                        existing.setStatus(ProjectStatus.ACTIVE);
                        existing.setUpdatedAt(Instant.now().toString());
                        log.info("Projet systeme '{}' reactive au demarrage - projectId={}",
                                DEFAULT_MISC_PROJECT_NAME, existing.getId());
                        return projectRepository.save(existing);
                    }
                    log.debug("Projet systeme '{}' deja present - projectId={}",
                            DEFAULT_MISC_PROJECT_NAME, existing.getId());
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Creation automatique du projet systeme '{}' au demarrage",
                            DEFAULT_MISC_PROJECT_NAME);
                    return create(DEFAULT_MISC_PROJECT_NAME);
                });
    }

    /**
     * Archive un projet (statut ACTIVE → ARCHIVED).
     * Un projet archivé reste consultable mais ne reçoit plus de nouvelles tâches.
     *
     * @param id l'ID du projet
     * @return l'entité mise à jour
     * @throws ProjectNotFoundException si le projet n'existe pas
     */
    @Transactional
    public ProjectEntity archive(String id) {
        return archive(id, null);
    }

    /**
     * Archive un projet et cascade l'archivage sur ses conversations ouvertes.
     *
     * @param id l'ID du projet
     * @param reason raison fonctionnelle d'archivage, optionnelle
     * @return l'entité mise à jour
     * @throws ProjectNotFoundException si le projet n'existe pas
     */
    @Transactional
    public ProjectEntity archive(String id, String reason) {
        ProjectEntity project = findOrThrow(id);
        ensureProjectMutable(project, "archive");
        List<ConversationEntity> openConversations =
                conversationRepository.findAllByProjectIdAndStatus(id, ConversationStatus.OPEN);
        openConversations.forEach(conversation -> {
            conversation.setStatus(ConversationStatus.ARCHIVED);
            conversationRepository.save(conversation);
        });
        project.setStatus(ProjectStatus.ARCHIVED);
        project.setUpdatedAt(Instant.now().toString());
        ProjectEntity saved = projectRepository.save(project);
        log.info("Projet archivé — id={}, name='{}', openConversationsArchived={}, reason='{}'",
                saved.getId(), saved.getName(), openConversations.size(), reason == null ? "" : reason);
        return saved;
    }

    /**
     * Désarchive un projet (statut ARCHIVED → ACTIVE).
     *
     * @param id l'ID du projet
     * @return l'entité mise à jour
     * @throws ProjectNotFoundException si le projet n'existe pas
     */
    @Transactional
    public ProjectEntity unarchive(String id) {
        ProjectEntity project = findOrThrow(id);
        ensureProjectMutable(project, "desarchive");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setUpdatedAt(Instant.now().toString());
        ProjectEntity saved = projectRepository.save(project);
        log.info("Projet désarchivé — id={}, name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Suppression
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Supprime un projet définitivement : efface ses workspaces externes en DB,
     * supprime la ligne projet (ON DELETE CASCADE nettoie project_workspace),
     * puis supprime récursivement le dossier filesystem.
     *
     * <p>L'opération est irréversible (pas de corbeille en E2-M1, §2.2).</p>
     *
     * @param id l'ID du projet
     * @throws ProjectNotFoundException si le projet n'existe pas
     * @throws UncheckedIOException     si la suppression du dossier échoue
     */
    @Transactional
    public void delete(String id) {
        ProjectEntity project = findOrThrow(id);
        ensureProjectMutable(project, "supprime");
        String workspacePath = project.getWorkspacePath();
        String name = project.getName();
        List<ConversationEntity> conversations = conversationRepository.findAllByProjectId(id);

        log.info("Suppression projet — purge mémoire de {} conversation(s), projectId={}",
                conversations.size(), id);
        conversations.forEach(conversation -> {
            log.debug("Purge ChatMemory projet — projectId={}, conversationId={}",
                    id, conversation.getId());
            chatMemory.clear(conversation.getId());
        });

        // Suppression DB — le CASCADE sur project_workspace nettoie les workspaces externes.
        workspaceRepository.deleteAllByProjectId(id);
        projectRepository.delete(project);
        log.info("Projet supprimé de la DB — id={}, name='{}'", id, name);

        // Suppression filesystem.
        Path dir = Paths.get(workspacePath);
        if (Files.exists(dir)) {
            deleteDirectoryRecursively(dir);
            log.info("Dossier projet supprimé — path='{}'", workspacePath);
        } else {
            log.debug("Dossier projet déjà absent, rien à supprimer — path='{}'", workspacePath);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mise à jour du nom
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Modifie le nom d'affichage d'un projet (sans changer son slug ni son dossier).
     *
     * @param id      l'ID du projet
     * @param newName le nouveau nom d'affichage
     * @return l'entité mise à jour
     * @throws ProjectNotFoundException si le projet n'existe pas
     */
    @Transactional
    public ProjectEntity updateName(String id, String newName) {
        ProjectEntity project = findOrThrow(id);
        ensureProjectMutable(project, "renomme");
        String oldName = project.getName();
        project.setName(newName);
        project.setUpdatedAt(Instant.now().toString());
        ProjectEntity saved = projectRepository.save(project);
        log.info("Nom de projet mis à jour — id={}, '{}' → '{}'", id, oldName, newName);
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workspaces externes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ajoute un dossier externe déclaré à un projet.
     * Le chemin doit être absolu et correspondre à un dossier existant.
     *
     * @param projectId l'ID du projet
     * @param path      le chemin absolu du dossier externe
     * @return l'entité workspace créée
     * @throws ProjectNotFoundException si le projet n'existe pas
     * @throws IllegalArgumentException si le chemin n'est pas absolu ou n'existe pas
     */
    @Transactional
    public ProjectWorkspaceEntity addWorkspace(String projectId, String path) {
        ProjectEntity project = findOrThrow(projectId);

        Path dir = Paths.get(path);
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Le chemin du dossier externe doit être absolu : " + path);
        }
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException(
                    "Le chemin fourni n'existe pas ou n'est pas un dossier : " + path);
        }

        ProjectWorkspaceEntity ws = ProjectWorkspaceEntity.builder()
                .id(UUID.randomUUID().toString())
                .project(project)
                .path(dir.toAbsolutePath().toString())
                .addedAt(Instant.now().toString())
                .build();

        ProjectWorkspaceEntity saved = workspaceRepository.save(ws);
        log.info("Dossier externe ajouté — wsId={}, projectId={}, path='{}'",
                saved.getId(), projectId, saved.getPath());
        return saved;
    }

    /**
     * Retire un dossier externe d'un projet (suppression de la ligne en DB uniquement —
     * le dossier filesystem n'est pas touché).
     *
     * @param wsId l'ID du workspace externe à retirer
     * @throws IllegalArgumentException si le workspace n'existe pas
     */
    @Transactional
    public void removeWorkspace(String wsId) {
        ProjectWorkspaceEntity ws = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace externe introuvable : " + wsId));
        String projectId = ws.getProject().getId();
        workspaceRepository.delete(ws);
        log.info("Dossier externe retiré — wsId={}, projectId={}, path='{}'",
                wsId, projectId, ws.getPath());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lectures
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne tous les projets, quel que soit leur statut.
     *
     * @return liste complète des projets
     */
    public List<ProjectEntity> findAll() {
        return projectRepository.findAll();
    }

    /**
     * Retourne les projets d'un statut donné.
     *
     * @param status le statut à filtrer
     * @return liste filtrée
     */
    public List<ProjectEntity> findByStatus(ProjectStatus status) {
        return projectRepository.findAllByStatus(status);
    }

    /**
     * Retourne un projet par son ID.
     *
     * @param id l'ID du projet
     * @return l'entité
     * @throws ProjectNotFoundException si le projet n'existe pas
     */
    public ProjectEntity findById(String id) {
        return findOrThrow(id);
    }

    public boolean isProtectedProject(ProjectEntity project) {
        return project != null && DEFAULT_MISC_PROJECT_SLUG.equals(project.getSanitizedName());
    }

    /**
     * Retourne les dossiers externes d'un projet.
     *
     * @param projectId l'ID du projet
     * @return liste des workspaces externes (peut être vide)
     */
    public List<ProjectWorkspaceEntity> findWorkspaces(String projectId) {
        return workspaceRepository.findAllByProjectId(projectId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers privés
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sanitise un nom en slug kebab-case ASCII minuscule.
     *
     * <p>Algorithme :</p>
     * <ol>
     *   <li>Normalisation Unicode NFD (décompose les accents).</li>
     *   <li>Suppression des diacritiques (caractères de la catégorie Non_Spacing_Mark).</li>
     *   <li>Passage en minuscule.</li>
     *   <li>Remplacement de tout caractère non alphanumérique par un tiret.</li>
     *   <li>Collapsage des tirets multiples consécutifs en un seul.</li>
     *   <li>Suppression des tirets en tête et en queue.</li>
     * </ol>
     *
     * @param name le nom brut saisi par l'utilisateur
     * @return le slug kebab-case, jamais null ni vide si le nom contient au moins un caractère valide
     */
    String sanitize(String name) {
        // 1. NFD + suppression diacritiques
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // 2. Minuscule, remplacement des non-[a-z0-9] par tiret
        String slug = normalized.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                // 3. Collapsage tirets + trim
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");

        log.debug("Sanitisation — '{}' → '{}'", name, slug);
        return slug;
    }

    private void checkSlugUniqueness(String slug) {
        if (projectRepository.findBySanitizedName(slug).isPresent()) {
            log.info("Conflit de nom détecté — slug='{}' déjà utilisé", slug);
            throw new ProjectNameConflictException(slug);
        }
    }

    private ProjectEntity findOrThrow(String id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }

    private Path resolveWorkspacePath(String slug) {
        return Paths.get(workspaceProperties.getRoot()).resolve(slug);
    }

    private void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
            log.debug("Dossier créé — path='{}'", path);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de créer le dossier workspace : " + path, e);
        }
    }

    private void initializeProjectContextFiles(Path workspacePath, String slug) {
        writeProjectFile(workspacePath.resolve(PROJECT_FILE_NAME), slug);
        writeFileIfAbsent(workspacePath.resolve(ROADMAP_FILE_NAME), ROADMAP_FILE_TEMPLATE);
    }

    private void writeProjectFile(Path path, String slug) {
        if (DEFAULT_MISC_PROJECT_SLUG.equals(slug)) {
            writeFile(path, DEFAULT_MISC_PROJECT_TEMPLATE);
            return;
        }
        writeFileIfAbsent(path, PROJECT_FILE_TEMPLATE);
    }

    private void writeFileIfAbsent(Path path, String content) {
        try {
            if (Files.notExists(path)) {
                Files.writeString(path, content);
                log.info("Fichier projet initialisé — path='{}'", path);
            } else {
                log.debug("Fichier projet déjà présent, initialisation ignorée — path='{}'", path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible d'initialiser le fichier projet : " + path, e);
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content);
            log.info("Fichier projet ecrit — path='{}'", path);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible d'ecrire le fichier projet : " + path, e);
        }
    }

    private void ensureProjectMutable(ProjectEntity project, String action) {
        if (isProtectedProject(project)) {
            throw new ProtectedProjectMutationException(
                    "Le projet systeme \"" + DEFAULT_MISC_PROJECT_NAME + "\" ne peut pas etre " + action + ".");
        }
    }

    private void deleteDirectoryRecursively(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(
                                    "Erreur lors de la suppression de : " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Erreur lors du parcours du dossier à supprimer : " + dir, e);
        }
    }
}
