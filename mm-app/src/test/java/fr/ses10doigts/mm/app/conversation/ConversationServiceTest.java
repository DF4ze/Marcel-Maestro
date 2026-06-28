package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'integration du {@link ConversationService}.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("very-slow")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
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

    @Test
    @DisplayName("startConversation cree une conversation ouverte avec activite vide")
    void startConversation_createsEntity() {
        ProjectEntity project = projectService.create("Test Projet Conv");

        ConversationEntity conv = conversationService.startConversation(project.getId());

        assertThat(conv.getId()).isNotNull().isNotBlank();
        assertThat(conv.getProjectId()).isEqualTo(project.getId());
        assertThat(conv.getStatus().name()).isEqualTo("OPEN");
        assertThat(conv.getStartedAt()).isNotNull();
        assertThat(conv.getMessageCount()).isZero();
        assertThat(conv.getLastMessageAt()).isNull();
        assertThat(conversationRepository.findById(conv.getId())).isPresent();
    }

    @Test
    @DisplayName("startConversation sur projet archive leve une exception")
    void startConversation_archivedProject_throwsException() {
        ProjectEntity project = projectService.create("Projet Archive");
        projectService.archive(project.getId());

        assertThatThrownBy(() -> conversationService.startConversation(project.getId()))
                .isInstanceOf(ProjectArchivedConversationException.class)
                .hasMessageContaining(project.getId());
    }

    @Test
    @DisplayName("addMessage met a jour messageCount et lastMessageAt")
    void addMessage_updatesConversationActivity() {
        ProjectEntity project = projectService.create("Projet Activite");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        conversationService.addMessage(conv.getId(), "Premier message");

        ConversationEntity reloaded = conversationService.getConversation(conv.getId());
        assertThat(reloaded.getMessageCount()).isEqualTo(1);
        assertThat(reloaded.getLastMessageAt()).isNotNull();
    }

    @Test
    @DisplayName("rename met a jour le titre")
    void rename_updatesTitle() {
        ProjectEntity project = projectService.create("Projet Rename");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        ConversationEntity renamed = conversationService.rename(conv.getId(), "Titre manuel");

        assertThat(renamed.getTitle()).isEqualTo("Titre manuel");
        assertThat(conversationService.getConversation(conv.getId()).getTitle()).isEqualTo("Titre manuel");
    }

    @Test
    @DisplayName("archive passe le statut a ARCHIVED et rejette un second archivage")
    void archive_updatesStatusAndRejectsDuplicate() {
        ProjectEntity project = projectService.create("Projet Archive Conversation");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        ConversationEntity archived = conversationService.archive(conv.getId());

        assertThat(archived.getStatus().name()).isEqualTo("ARCHIVED");
        assertThatThrownBy(() -> conversationService.archive(conv.getId()))
                .isInstanceOf(ConversationAlreadyArchivedException.class);
    }

    @Test
    @DisplayName("chat refuse tout nouveau message sur conversation archivee")
    void chat_archivedConversation_throwsReadOnlyException() {
        ProjectEntity project = projectService.create("Projet Archive ReadOnly");
        ConversationEntity conv = conversationService.startConversation(project.getId());
        conversationService.archive(conv.getId());

        assertThatThrownBy(() -> conversationService.chat(conv.getId(), "Message interdit"))
                .isInstanceOf(ArchivedConversationReadOnlyException.class);
    }

    @Test
    @DisplayName("listByProject filtre par statut et trie les conversations par activite")
    void listByProject_filtersAndSortsByActivity() throws Exception {
        ProjectEntity project = projectService.create("Projet Liste Activite");
        ConversationEntity oldest = conversationService.startConversation(project.getId());
        ConversationEntity newest = conversationService.startConversation(project.getId());
        ConversationEntity noMessage = conversationService.startConversation(project.getId());

        conversationService.addMessage(oldest.getId(), "Ancienne activite");
        Thread.sleep(5L);
        conversationService.addMessage(newest.getId(), "Nouvelle activite");
        conversationService.archive(noMessage.getId());

        List<ConversationEntity> openConversations = conversationService.listByProject(project.getId(), "OPEN");
        List<ConversationEntity> archivedConversations = conversationService.listByProject(project.getId(), "ARCHIVED");
        List<ConversationEntity> allConversations = conversationService.listByProject(project.getId(), "ALL");

        assertThat(openConversations).extracting(ConversationEntity::getId)
                .containsExactly(newest.getId(), oldest.getId());
        assertThat(archivedConversations).extracting(ConversationEntity::getId)
                .containsExactly(noMessage.getId());
        assertThat(allConversations).extracting(ConversationEntity::getId)
                .containsExactly(newest.getId(), oldest.getId(), noMessage.getId());
    }

    @Test
    @DisplayName("deux conversations du meme projet ne partagent pas leurs messages")
    void memoryIsolation_betweenConversations() {
        ProjectEntity project = projectService.create("Projet Isolation");
        ConversationEntity conv1 = conversationService.startConversation(project.getId());
        ConversationEntity conv2 = conversationService.startConversation(project.getId());

        conversationService.addMessage(conv1.getId(), "Message dans conv1");
        conversationService.addMessage(conv1.getId(), "Deuxieme message dans conv1");
        conversationService.addMessage(conv2.getId(), "Message dans conv2");

        List<Message> messagesConv1 = conversationService.getMessages(conv1.getId());
        List<Message> messagesConv2 = conversationService.getMessages(conv2.getId());

        assertThat(messagesConv1).hasSize(2);
        assertThat(messagesConv2).hasSize(1);
        assertThat(messagesConv2.getFirst().getText()).isEqualTo("Message dans conv2");
    }

    @Test
    @DisplayName("deux projets differents ont des partitions memoire separees")
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

    @Test
    @DisplayName("les messages restent lisibles directement depuis JdbcChatMemoryRepository")
    void jdbcPersistence_messagesReadableDirectlyFromRepository() {
        ProjectEntity project = projectService.create("Projet Persist");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        conversationService.addMessage(conv.getId(), "Message persiste en JDBC");

        List<Message> messages = jdbcChatMemoryRepository.findByConversationId(conv.getId());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getText()).isEqualTo("Message persiste en JDBC");
    }

    @Test
    @DisplayName("findConversationIds retourne les IDs persistes")
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
    @DisplayName("delete purge la memoire Spring AI avant suppression")
    void deleteConversation_clearsChatMemory() {
        ProjectEntity project = projectService.create("Projet Delete Conv");
        ConversationEntity conv = conversationService.startConversation(project.getId());
        conversationService.addMessage(conv.getId(), "Message a supprimer");

        assertThat(jdbcChatMemoryRepository.findByConversationId(conv.getId())).hasSize(1);

        conversationService.delete(conv.getId());

        assertThat(conversationRepository.findById(conv.getId())).isEmpty();
        assertThat(jdbcChatMemoryRepository.findByConversationId(conv.getId())).isEmpty();
    }

    @Test
    @DisplayName("listByProject sans filtre retourne uniquement les conversations du projet")
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
    @DisplayName("getConversation leve ConversationNotFoundException si ID inconnu")
    void getConversation_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> conversationService.getConversation("id-inconnu"))
                .isInstanceOf(ConversationNotFoundException.class)
                .hasMessageContaining("id-inconnu");
    }

    @Test
    @DisplayName("AgentContext conserve projectId et conversationId")
    void agentContext_projectIdAndConversationIdNonNull() {
        ProjectEntity project = projectService.create("Projet Context");
        ConversationEntity conv = conversationService.startConversation(project.getId());

        fr.ses10doigts.mm.core.agent.AgentContext ctx = fr.ses10doigts.mm.core.agent.AgentContext
                .of("default", project.getId(), conv.getId(), "task-123");

        assertThat(ctx.projectId()).isNotNull().isEqualTo(project.getId());
        assertThat(ctx.conversationId()).isNotNull().isEqualTo(conv.getId());
    }
}
