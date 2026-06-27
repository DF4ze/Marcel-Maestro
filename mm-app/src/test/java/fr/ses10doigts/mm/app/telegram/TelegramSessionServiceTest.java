package fr.ses10doigts.mm.app.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'intégration de {@link TelegramSessionService} (E2-M5).
 *
 * <p>Vérifie :</p>
 * <ul>
 *   <li>Résolution du projet actif depuis la session.</li>
 *   <li>Repli sur le premier projet ACTIVE si aucune session active.</li>
 *   <li>Switch de projet actif par chatId.</li>
 *   <li>Recherche de projet par nom / slug insensible à la casse.</li>
 *   <li>Isolation des sessions entre chatIds distincts.</li>
 *   <li>Comptage des conversations ouvertes par projet.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TelegramSessionServiceTest {

    @Autowired
    private TelegramSessionService sessionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private ProjectEntity projectA;
    private ProjectEntity projectB;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAll();
        projectRepository.deleteAll();
        projectA = projectService.create("Projet Alpha");
        projectB = projectService.create("Projet Beta");
    }

    @AfterEach
    void tearDown() {
        conversationRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Résolution du projet actif
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getActiveProjectId — retourne empty si aucune session")
    void getActiveProjectId_noSession_returnsEmpty() {
        assertThat(sessionService.getActiveProjectId(999L)).isEmpty();
    }

    @Test
    @DisplayName("resolveProjectId — repli sur premier projet ACTIVE si pas de session")
    void resolveProjectId_noSession_fallsBackToFirstActiveProject() {
        Optional<String> resolved = sessionService.resolveProjectId(999L);

        assertThat(resolved).isPresent();
        // Doit être l'un des deux projets actifs créés
        assertThat(List.of(projectA.getId(), projectB.getId())).contains(resolved.get());
    }

    @Test
    @DisplayName("resolveProjectId — retourne empty si aucun projet ACTIVE")
    void resolveProjectId_noActiveProject_returnsEmpty() {
        projectService.archive(projectA.getId());
        projectService.archive(projectB.getId());

        assertThat(sessionService.resolveProjectId(999L)).isEmpty();
    }

    @Test
    @DisplayName("resolveProjectId — utilise la session active si définie")
    void resolveProjectId_withActiveSession_returnsSessionProject() {
        sessionService.setActiveProject(42L, projectB.getId(), projectB.getName());

        Optional<String> resolved = sessionService.resolveProjectId(42L);

        assertThat(resolved).isPresent().contains(projectB.getId());
    }

    @Test
    @DisplayName("resolveProjectId — session invalidée si projet archivé depuis le switch")
    void resolveProjectId_sessionStale_archivedProject_invalidatedAndFallback() {
        // Session pointant sur projectA
        sessionService.setActiveProject(42L, projectA.getId(), projectA.getName());

        // projectA est archivé entre-temps
        projectService.archive(projectA.getId());

        // resolveProjectId doit invalider la session et retourner le fallback (projectB)
        Optional<String> resolved = sessionService.resolveProjectId(42L);

        assertThat(resolved).isPresent().contains(projectB.getId());
        // La session doit avoir été purgée
        assertThat(sessionService.getActiveProjectId(42L)).isEmpty();
    }

    @Test
    @DisplayName("resolveProjectId — session invalidée si projet supprimé, retourne empty si aucun autre projet")
    void resolveProjectId_sessionStale_deletedProject_returnsEmptyWhenNoOtherActive() {
        // Uniquement projectA, pas de projectB
        projectService.archive(projectB.getId());
        sessionService.setActiveProject(42L, projectA.getId(), projectA.getName());

        // projectA archivé
        projectService.archive(projectA.getId());

        // Aucun projet ACTIVE restant → empty
        assertThat(sessionService.resolveProjectId(42L)).isEmpty();
        // Session purgée
        assertThat(sessionService.getActiveProjectId(42L)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Switch et isolation entre chatIds
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setActiveProject — switch de projet actif pour un chatId")
    void setActiveProject_updatesSession() {
        sessionService.setActiveProject(1L, projectA.getId(), projectA.getName());
        assertThat(sessionService.getActiveProjectId(1L)).contains(projectA.getId());

        sessionService.setActiveProject(1L, projectB.getId(), projectB.getName());
        assertThat(sessionService.getActiveProjectId(1L)).contains(projectB.getId());
    }

    @Test
    @DisplayName("Sessions isolées — deux chatIds ont des sessions indépendantes")
    void sessions_isolatedByChatId() {
        sessionService.setActiveProject(1L, projectA.getId(), projectA.getName());
        sessionService.setActiveProject(2L, projectB.getId(), projectB.getName());

        assertThat(sessionService.getActiveProjectId(1L)).contains(projectA.getId());
        assertThat(sessionService.getActiveProjectId(2L)).contains(projectB.getId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recherche par nom
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findActiveProjectByName — correspondance exacte insensible à la casse")
    void findActiveProjectByName_caseInsensitiveMatch() {
        Optional<ProjectEntity> found = sessionService.findActiveProjectByName("projet alpha");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(projectA.getId());
    }

    @Test
    @DisplayName("findActiveProjectByName — correspondance sur slug sanitisé")
    void findActiveProjectByName_matchesSanitizedName() {
        // "Projet Alpha" → slug "projet-alpha"
        Optional<ProjectEntity> found = sessionService.findActiveProjectByName("projet-alpha");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(projectA.getId());
    }

    @Test
    @DisplayName("findActiveProjectByName — projet archivé non retourné")
    void findActiveProjectByName_archivedProject_notFound() {
        projectService.archive(projectA.getId());

        assertThat(sessionService.findActiveProjectByName("Projet Alpha")).isEmpty();
    }

    @Test
    @DisplayName("findActiveProjectByName — nom inconnu retourne empty")
    void findActiveProjectByName_unknownName_returnsEmpty() {
        assertThat(sessionService.findActiveProjectByName("Projet Inexistant")).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Comptage des conversations ouvertes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countOpenConversations — retourne 0 si aucune conversation")
    void countOpenConversations_none_returnsZero() {
        assertThat(sessionService.countOpenConversations(projectA.getId())).isZero();
    }

    @Test
    @DisplayName("countOpenConversations — compte correctement les conversations ouvertes")
    void countOpenConversations_returnsCorrectCount() {
        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectB.getId());

        assertThat(sessionService.countOpenConversations(projectA.getId())).isEqualTo(2);
        assertThat(sessionService.countOpenConversations(projectB.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("countOpenConversationsByProjects — une seule requête batch pour plusieurs projets")
    void countOpenConversationsByProjects_batchQuery() {
        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectB.getId());

        Map<String, Long> counts = sessionService.countOpenConversationsByProjects(
                List.of(projectA.getId(), projectB.getId()));

        assertThat(counts).containsEntry(projectA.getId(), 2L)
                          .containsEntry(projectB.getId(), 1L);
    }

    @Test
    @DisplayName("countOpenConversationsByProjects — projet sans conversation absent de la map")
    void countOpenConversationsByProjects_projectWithNoConversations_absentFromMap() {
        // Aucune conversation créée pour projectA
        conversationService.startConversation(projectB.getId());

        Map<String, Long> counts = sessionService.countOpenConversationsByProjects(
                List.of(projectA.getId(), projectB.getId()));

        // projectA absent (0 conversation = non retourné par GROUP BY)
        assertThat(counts).doesNotContainKey(projectA.getId())
                          .containsEntry(projectB.getId(), 1L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listActiveProjects
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listActiveProjects — retourne uniquement les projets ACTIVE")
    void listActiveProjects_returnsOnlyActiveProjects() {
        projectService.archive(projectA.getId());

        List<ProjectEntity> active = sessionService.listActiveProjects();

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getId()).isEqualTo(projectB.getId());
    }
}
