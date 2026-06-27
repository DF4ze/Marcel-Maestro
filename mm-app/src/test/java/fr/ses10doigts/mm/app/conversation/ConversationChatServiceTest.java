package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import fr.ses10doigts.mm.app.config.ScriptedChatClientTestConfiguration;
import fr.ses10doigts.mm.app.config.SyncAsyncTestConfiguration;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.app.support.ScriptedChatModel;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests E3-M1 du flux conversationnel LLM via {@link ConversationService}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({SyncAsyncTestConfiguration.class, ScriptedChatClientTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
class ConversationChatServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;

    @Autowired
    private ScriptedChatModel scriptedChatModel;

    @MockitoBean
    private ConversationTitleService titleService;

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
    @DisplayName("chat — retourne la réponse LLM et persiste USER + ASSISTANT")
    void chat_returnsResponseAndPersistsAssistant() {
        ProjectEntity project = projectService.create("Projet Chat");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Bonjour, je peux t'aider.");

        String response = conversationService.chat(conversation.getId(), "Salut Marcel");

        assertThat(response).isEqualTo("Bonjour, je peux t'aider.");
        List<Message> messages = conversationService.getMessages(conversation.getId());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(messages.get(0).getText()).isEqualTo("Salut Marcel");
        assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(messages.get(1).getText()).isEqualTo("Bonjour, je peux t'aider.");
        assertThat(jdbcChatMemoryRepository.findByConversationId(conversation.getId())).hasSize(2);
        verify(titleService).generateTitle(conversation.getId(), "Salut Marcel");
    }

    @Test
    @DisplayName("chat — recharge l'historique sur l'appel suivant et ne régénère pas le titre")
    void chat_reloadHistoryOnSecondCall() {
        ProjectEntity project = projectService.create("Projet Historique");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Réponse 1");
        scriptedChatModel.reply("Réponse 2");

        conversationService.chat(conversation.getId(), "Message 1");
        conversationService.chat(conversation.getId(), "Message 2");

        verify(titleService, times(1)).generateTitle(conversation.getId(), "Message 1");
        verify(titleService, never()).generateTitle(conversation.getId(), "Message 2");

        List<Prompt> prompts = scriptedChatModel.prompts();
        assertThat(prompts).hasSize(2);
        List<String> secondPromptTexts = prompts.get(1).getInstructions().stream()
                .map(Message::getText)
                .toList();
        assertThat(secondPromptTexts).contains("Message 1", "Réponse 1", "Message 2");
    }

    @Test
    @DisplayName("chat — isole les historiques entre deux conversationId distincts")
    void chat_isolatesConversations() {
        ProjectEntity project = projectService.create("Projet Isolation Chat");
        ConversationEntity conversationA = conversationService.startConversation(project.getId());
        ConversationEntity conversationB = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Réponse A1");
        scriptedChatModel.reply("Réponse B1");
        scriptedChatModel.reply("Réponse A2");

        conversationService.chat(conversationA.getId(), "Message A1");
        conversationService.chat(conversationB.getId(), "Message B1");
        conversationService.chat(conversationA.getId(), "Message A2");

        assertThat(conversationService.getMessages(conversationA.getId())).hasSize(4);
        assertThat(conversationService.getMessages(conversationB.getId())).hasSize(2);

        List<String> thirdPromptTexts = scriptedChatModel.prompts().get(2).getInstructions().stream()
                .map(Message::getText)
                .toList();
        assertThat(thirdPromptTexts).contains("Message A1", "Réponse A1", "Message A2");
        assertThat(thirdPromptTexts).doesNotContain("Message B1", "Réponse B1");
    }
}
