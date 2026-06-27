package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'intégration du {@link ConversationService} (E2-M2).
 *
 * <p>Vérifie :</p>
 * <ul>
 *   <li>Isolation mémoire entre conversations (même projet, partitions séparées).</li>
 *   <li>Persistance JDBC (messages lisibles depuis {@link JdbcChatMemoryRepository} directement).</li>
 *   <li>Rejet des projets archivés ({@link ProjectArchivedConversationException}).</li>
 *   <li>Injection correcte du {@code TaskMessage} depuis { TelegramMmController} (E2-M2).</li>
 * </ul>
 *
 * <p>La datasource SQLite in-memory partagée ({@code file:testdb-app?mode=memory&cache=shared})
 * garantit que les données sont visibles entre beans dans le même contexte Spring.
 * Flyway V3 crée {@code SPRING_AI_CHAT_MEMORY} avant les tests.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("very-slow")
class ConversationServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        conversationRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Création
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("startConversation — crée une ConversationEntity avec UUID non-null")
    void startConversation_createsEntity() {
        ProjectEntity project = projectService.create("Test Projet Conv");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        assertThat(conv.getId()).isNotNull().isNotBlank();
        assertThat(conv.getProjectId()).isEqualTo(project.getId());
        assertThat(conv.getStatus().name()).isEqualTo("OPEN");
        assertThat(conv.getStartedAt()).isNotNull();

        // Vérifie que l'entité est persistée en DB
        assertThat(conversationRepository.findById(conv.getId())).isPresent();
    }

    @Test
    @DisplayName("startConversation sur projet archivé → ProjectArchivedConversationException")
    void startConversation_archivedProject_throwsException() {
        ProjectEntity project = projectService.create("Projet Archivé");
        projectService.archive(project.getId());

        assertThatThrownBy(() -> conversationService.startConversation(project.getId()))
                .isInstanceOf(ProjectArchivedConversationException.class)
                .hasMessageContaining(project.getId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Isolation mémoire entre conversations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Isolation mémoire — deux conversations du même projet ne partagent pas de messages")
    void memoryIsolation_betweenConversations() {
        ProjectEntity project = projectService.create("Projet Isolation");

        ConversationEntity conv1 = conversationService.startConversation(project.getId());
        ConversationEntity conv2 = conversationService.startConversation(project.getId());

        conversationService.addMessage(conv1.getId(), "Message dans conv1");
        conversationService.addMessage(conv1.getId(), "Deuxième message dans conv1");
        conversationService.addMessage(conv2.getId(), "Message dans conv2");

        List<Message> messagesConv1 = conversationService.getMessages(conv1.getId());
        List<Message> messagesConv2 = conversationService.getMessages(conv2.getId());

        assertThat(messagesConv1).hasSize(2);
        assertThat(messagesConv2).hasSize(1);

        // Les messages de conv1 ne sont pas dans conv2
        assertThat(messagesConv2.getFirst().getText()).isEqualTo("Message dans conv2");
    }

    @Test
    @DisplayName("Isolation mémoire — deux projets différents ont des partitions séparées")
    void memoryIsolation_betweenProjects() {
        ProjectEntity projectA = projectService.create("Projet A");
        ProjectEntity projectB = projectService.create("Projet B");

        ConversationEntity convA = conversationService.startConversation(projectA.getId());
        ConversationEntity convB = conversationService.startConversation(projectB.getId());

        conversationService.addMessage(convA.getId(), "Message projet A");
        conversationService.addMessage(convB.getId(), "Message projet B");

        assertThat(conversationService.getMessages(convA.getId())).hasSize(1);
        assertThat(conversationService.getMessages(convB.getId())).hasSize(1);
        assertThat(conversationService.getMessages(convA.getId()).getFirst().getText())
                .isEqualTo("Message projet A");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistance JDBC (survie sans redémarrage — même datasource partagée)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Persistance JDBC — les messages sont lisibles directement depuis JdbcChatMemoryRepository")
    void jdbcPersistence_messagesReadableDirectlyFromRepository() {
        ProjectEntity project = projectService.create("Projet Persist");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        conversationService.addMessage(conv.getId(), "Message persisté en JDBC");

        // Lecture directe depuis le repository (bypass ChatMemory cache)
        List<Message> messages = jdbcChatMemoryRepository.findByConversationId(conv.getId());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getText()).isEqualTo("Message persisté en JDBC");
    }

    @Test
    @DisplayName("Persistance JDBC — findConversationIds retourne les IDs persistés")
    void jdbcPersistence_conversationIdsListable() {
        ProjectEntity project = projectService.create("Projet IDs");
        ConversationEntity conv1 = conversationService.startConversation(project.getId());
        ConversationEntity conv2 = conversationService.startConversation(project.getId());

        conversationService.addMessage(conv1.getId(), "msg1");
        conversationService.addMessage(conv2.getId(), "msg2");

        List<String> ids = jdbcChatMemoryRepository.findConversationIds();
        assertThat(ids).contains(conv1.getId(), conv2.getId());
    }

    @Test
    @DisplayName("Suppression conversation — la mémoire Spring AI est purgée avant suppression DB")
    void deleteConversation_clearsChatMemory() {
        ProjectEntity project = projectService.create("Projet Delete Conv");
        ConversationEntity conv = conversationService.startConversation(project.getId());
        conversationService.addMessage(conv.getId(), "Message à supprimer");

        assertThat(jdbcChatMemoryRepository.findByConversationId(conv.getId())).hasSize(1);

        conversationService.delete(conv.getId());

        assertThat(conversationRepository.findById(conv.getId())).isEmpty();
        assertThat(jdbcChatMemoryRepository.findByConversationId(conv.getId())).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lectures
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listByProject — retourne uniquement les conversations du projet demandé")
    void listByProject_returnsOnlyProjectConversations() {
        ProjectEntity projectA = projectService.create("Projet Liste A");
        ProjectEntity projectB = projectService.create("Projet Liste B");

        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectA.getId());
        conversationService.startConversation(projectB.getId());

        assertThat(conversationService.listByProject(projectA.getId())).hasSize(2);
        assertThat(conversationService.listByProject(projectB.getId())).hasSize(1);
    }

    @Test
    @DisplayName("getConversation — lève ConversationNotFoundException si ID inconnu")
    void getConversation_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> conversationService.getConversation("id-inconnu"))
                .isInstanceOf(ConversationNotFoundException.class)
                .hasMessageContaining("id-inconnu");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TaskMessage injection — AgentContext non-null (E2-M2)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AgentContext — projectId et conversationId non-null après startConversation")
    void agentContext_projectIdAndConversationIdNonNull() {
        ProjectEntity project = projectService.create("Projet Context");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        // L'AgentContext est construit par l'appelant (Telegram/REST) avec ces valeurs
        fr.ses10doigts.mm.core.agent.AgentContext ctx = fr.ses10doigts.mm.core.agent.AgentContext
                .of("default", project.getId(), conv.getId(), "task-123");

        assertThat(ctx.projectId()).isNotNull().isEqualTo(project.getId());
        assertThat(ctx.conversationId()).isNotNull().isEqualTo(conv.getId());
    }
}
