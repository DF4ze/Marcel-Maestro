package fr.ses10doigts.mm.app.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'integration de {@link TelegramSessionService}.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
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

    @Test
    @DisplayName("getActiveProjectId retourne empty si aucune session")
    void getActiveProjectId_noSession_returnsEmpty() {
        assertThat(sessionService.getActiveProjectId(999L)).isEmpty();
    }

    @Test
    @DisplayName("resolveProjectId replie sur un projet actif si pas de session")
    void resolveProjectId_noSession_fallsBackToFirstActiveProject() {
        Optional<String> resolved = sessionService.resolveProjectId(999L);

        assertThat(resolved).isPresent();
        assertThat(List.of(projectA.getId(), projectB.getId())).contains(resolved.get());
    }

    @Test
    @DisplayName("resolveProjectId retourne empty si aucun projet actif")
    void resolveProjectId_noActiveProject_returnsEmpty() {
        projectService.archive(projectA.getId());
        projectService.archive(projectB.getId());

        assertThat(sessionService.resolveProjectId(999L)).isEmpty();
    }

    @Test
    @DisplayName("resolveProjectId utilise la session active si definie")
    void resolveProjectId_withActiveSession_returnsSessionProject() {
        sessionService.setActiveProject(42L, projectB.getId(), projectB.getName());

        Optional<String> resolved = sessionService.resolveProjectId(42L);

        assertThat(resolved).contains(projectB.getId());
    }

    @Test
    @DisplayName("resolveProjectId invalide la session si le projet est archive")
    void resolveProjectId_sessionStale_archivedProject_invalidatedAndFallback() {
        sessionService.setActiveProject(42L, projectA.getId(), projectA.getName());
        projectService.archive(projectA.getId());

        Optional<String> resolved = sessionService.resolveProjectId(42L);

        assertThat(resolved).contains(projectB.getId());
        assertThat(sessionService.getActiveProjectId(42L)).isEmpty();
    }

    @Test
    @DisplayName("resolveProjectId retourne empty si plus aucun projet actif")
    void resolveProjectId_sessionStale_deletedProject_returnsEmptyWhenNoOtherActive() {
        projectService.archive(projectB.getId());
        sessionService.setActiveProject(42L, projectA.getId(), projectA.getName());
        projectService.archive(projectA.getId());

        assertThat(sessionService.resolveProjectId(42L)).isEmpty();
        assertThat(sessionService.getActiveProjectId(42L)).isEmpty();
    }

    @Test
    @DisplayName("setActiveProject vide la conversation active")
    void setActiveProject_updatesSession() {
        sessionService.setActiveConversationId(1L, "conv-initiale");
        sessionService.setActiveProject(1L, projectA.getId(), projectA.getName());
        assertThat(sessionService.getActiveProjectId(1L)).contains(projectA.getId());
        assertThat(sessionService.getActiveConversationId(1L)).isEmpty();

        sessionService.setActiveConversationId(1L, "conv-projet-a");
        sessionService.setActiveProject(1L, projectB.getId(), projectB.getName());
        assertThat(sessionService.getActiveProjectId(1L)).contains(projectB.getId());
        assertThat(sessionService.getActiveConversationId(1L)).isEmpty();
    }

    @Test
    @DisplayName("sessions isolees entre chatIds")
    void sessions_isolatedByChatId() {
        sessionService.setActiveProject(1L, projectA.getId(), projectA.getName());
        sessionService.setActiveProject(2L, projectB.getId(), projectB.getName());
        sessionService.setActiveConversationId(1L, "conv-a");
        sessionService.setActiveConversationId(2L, "conv-b");

        assertThat(sessionService.getActiveProjectId(1L)).contains(projectA.getId());
        assertThat(sessionService.getActiveProjectId(2L)).contains(projectB.getId());
        assertThat(sessionService.getActiveConversationId(1L)).contains("conv-a");
        assertThat(sessionService.getActiveConversationId(2L)).contains("conv-b");
    }

    @Test
    @DisplayName("clearActiveConversationId supprime uniquement le chat cible")
    void activeConversation_clear_removesOnlyTargetChat() {
        sessionService.setActiveConversationId(1L, "conv-a");
        sessionService.setActiveConversationId(2L, "conv-b");

        sessionService.clearActiveConversationId(1L);

        assertThat(sessionService.getActiveConversationId(1L)).isEmpty();
        assertThat(sessionService.getActiveConversationId(2L)).contains("conv-b");
    }

    @Test
    @DisplayName("findActiveProjectByName matche nom exact insensible a la casse")
    void findActiveProjectByName_caseInsensitiveMatch() {
        Optional<ProjectEntity> found = sessionService.findActiveProjectByName("projet alpha");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(projectA.getId());
    }

    @Test
    @DisplayName("findActiveProjectByName matche le slug")
    void findActiveProjectByName_matchesSanitizedName() {
        Optional<ProjectEntity> found = sessionService.findActiveProjectByName("projet-alpha");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(projectA.getId());
    }

    @Test
    @DisplayName("findActiveProjectByName ignore un projet archive")
    void findActiveProjectByName_archivedProject_notFound() {
        projectService.archive(projectA.getId());

        assertThat(sessionService.findActiveProjectByName("Projet Alpha")).isEmpty();
    }

    @Test
    @DisplayName("findActiveProjectByName retourne empty si inconnu")
    void findActiveProjectByName_unknownName_returnsEmpty() {
        assertThat(sessionService.findActiveProjectByName("Projet Inexistant")).isEmpty();
    }

    @Test
    @DisplayName("findActiveProjectsByQuery supporte prefixe et contient")
    void findActiveProjectsByQuery_supportsPrefixAndContains() {
        List<ProjectEntity> byPrefix = sessionService.findActiveProjectsByQuery("projet al", 10);
        List<ProjectEntity> byContains = sessionService.findActiveProjectsByQuery("beta", 10);

        assertThat(byPrefix).extracting(ProjectEntity::getId).contains(projectA.getId());
        assertThat(byContains).extracting(ProjectEntity::getId).contains(projectB.getId());
    }

    @Test
    @DisplayName("findActiveProjectsByQuery vide retourne les projets actifs")
    void findActiveProjectsByQuery_blank_returnsActiveProjects() {
        List<ProjectEntity> results = sessionService.findActiveProjectsByQuery("", 10);

        assertThat(results).extracting(ProjectEntity::getId)
                .contains(projectA.getId(), projectB.getId());
    }

    @Test
    @DisplayName("countOpenConversations retourne zero si aucune conversation")
    void countOpenConversations_none_returnsZero() {
        assertThat(sessionService.countOpenConversations(projectA.getId())).isZero();
    }

    @Test
    @DisplayName("countOpenConversations compte correctement")
    void countOpenConversations_returnsCorrectCount() {
        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectB.getId());

        assertThat(sessionService.countOpenConversations(projectA.getId())).isEqualTo(2);
        assertThat(sessionService.countOpenConversations(projectB.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("countOpenConversationsByProjects agrege en batch")
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
    @DisplayName("countOpenConversationsByProjects omet les projets sans conversation")
    void countOpenConversationsByProjects_projectWithNoConversations_absentFromMap() {
        conversationService.startConversation(projectB.getId());

        Map<String, Long> counts = sessionService.countOpenConversationsByProjects(
                List.of(projectA.getId(), projectB.getId()));

        assertThat(counts).doesNotContainKey(projectA.getId())
                .containsEntry(projectB.getId(), 1L);
    }

    @Test
    @DisplayName("listOpenConversationsForProject trie par activite recente")
    void listOpenConversationsForProject_sortedByActivity() throws Exception {
        ConversationEntity older = conversationService.startConversation(projectA.getId());
        ConversationEntity newer = conversationService.startConversation(projectA.getId());

        conversationService.addMessage(older.getId(), "Message ancien");
        Thread.sleep(5L);
        conversationService.addMessage(newer.getId(), "Message recent");

        List<ConversationEntity> conversations = sessionService.listOpenConversationsForProject(projectA.getId(), 5);

        assertThat(conversations).extracting(ConversationEntity::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("conversationSuggestions se resolvent par index uniquement si OPEN")
    void conversationSuggestions_resolveByIndex_onlyOpen() {
        ConversationEntity openConversation = conversationService.startConversation(projectA.getId());
        ConversationEntity archivedConversation = conversationService.startConversation(projectA.getId());
        conversationService.archive(archivedConversation.getId());

        sessionService.setConversationSuggestions(42L, List.of(openConversation.getId(), archivedConversation.getId()));

        assertThat(sessionService.resolveConversationSuggestion(42L, 0))
                .hasValueSatisfying(conversation ->
                        assertThat(conversation.getId()).isEqualTo(openConversation.getId()));
        assertThat(sessionService.resolveConversationSuggestion(42L, 1)).isEmpty();
    }

    @Test
    @DisplayName("switchSuggestions se resolvent par index")
    void switchSuggestions_resolveByIndex() {
        sessionService.setSwitchSuggestions(42L, List.of(projectA.getId(), projectB.getId()));

        assertThat(sessionService.resolveSwitchSuggestion(42L, 0))
                .hasValueSatisfying(project -> assertThat(project.getId()).isEqualTo(projectA.getId()));
        assertThat(sessionService.resolveSwitchSuggestion(42L, 1))
                .hasValueSatisfying(project -> assertThat(project.getId()).isEqualTo(projectB.getId()));
        assertThat(sessionService.resolveSwitchSuggestion(42L, 2)).isEmpty();
    }

    @Test
    @DisplayName("listActiveProjects retourne uniquement les projets actifs")
    void listActiveProjects_returnsOnlyActiveProjects() {
        projectService.archive(projectA.getId());

        List<ProjectEntity> active = sessionService.listActiveProjects();

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getId()).isEqualTo(projectB.getId());
    }
}
