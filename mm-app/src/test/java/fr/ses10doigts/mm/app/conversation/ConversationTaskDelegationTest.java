package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.ses10doigts.mm.app.config.SyncAsyncTestConfiguration;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests E3-M2 de délégation de tâche depuis la conversation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SyncAsyncTestConfiguration.class)
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
class ConversationTaskDelegationTest {

    @MockitoBean
    private ChatClient chatClient;

    @MockitoBean
    private TaskQueue taskQueue;

    @MockitoBean
    private Dispatcher dispatcher;

    @MockitoBean
    private ConversationTitleService titleService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ChatAgent chatAgent;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentContextHolder agentContextHolder;

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
    @DisplayName("chat — délégation déclenchée : submit_task soumet une TaskMessage cohérente")
    void chat_delegatesTaskWithCurrentContext() {
        ProjectEntity project = projectService.create("Projet Délégation");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        mockChatClientWithDelegation(
                "Lance un build Maven du projet courant",
                "Je lance ça, tu recevras une notification Telegram quand ce sera terminé.");

        String response = conversationService.chat(conversation.getId(), "Lance un build Maven du projet courant");

        assertThat(response).isEqualTo("Je lance ça, tu recevras une notification Telegram quand ce sera terminé.");

        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(taskQueue).submit(captor.capture());
        TaskMessage submitted = captor.getValue();
        assertThat(submitted.taskId()).isNotBlank();
        assertThat(submitted.type().name()).isEqualTo("USER_REQUEST");
        // Routage deterministe via le qualificateur : "build Maven" -> BUILD -> codex.
        assertThat(submitted.assignee()).isEqualTo("codex");
        assertThat(submitted.content()).isEqualTo("Lance un build Maven du projet courant");
        assertThat(submitted.ctx().projectId()).isEqualTo(project.getId());
        assertThat(submitted.ctx().conversationId()).isEqualTo(conversation.getId());
        assertThat(submitted.ctx().taskId()).isEqualTo(submitted.taskId());
    }

    @Test
    @DisplayName("chat — question simple : aucune délégation et réponse non nulle")
    void chat_simpleQuestionDoesNotDelegate() {
        ProjectEntity project = projectService.create("Projet Question");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        mockChatClientWithoutDelegation("Java 21 apporte notamment les virtual threads et les record patterns.");

        String response = conversationService.chat(conversation.getId(), "Qu'est-ce que Java 21 apporte comme nouveautés ?");

        assertThat(response).isNotNull().isNotBlank();
        verify(taskQueue, never()).submit(any(TaskMessage.class));
    }

    @Test
    @DisplayName("POST /messages — après submit_task la réponse assistant reste retournée en JSON")
    void postMessages_returnsAssistantResponseAfterDelegation() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Délégation");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        mockChatClientWithDelegation(
                "Lance un build Maven du projet courant",
                "Je lance ça, tu recevras une notification Telegram quand ce sera terminé.");

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        project.getId(), conversation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Lance un build Maven du projet courant"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.content")
                        .value("Je lance ça, tu recevras une notification Telegram quand ce sera terminé."));

        verify(taskQueue).submit(any(TaskMessage.class));
    }

    @Test
    @DisplayName("submit_task â€” Dispatcher absent ou arrÃªtÃ© : exception explicite")
    void submitTask_throwsWhenDispatcherUnavailable() {
        when(dispatcher.isRunning()).thenReturn(false);
        agentContextHolder.bind(fr.ses10doigts.mm.core.agent.AgentContext.of(
                "default", "project-1", "conversation-1", "chat-1"));

        try {
            assertThatThrownBy(() -> chatAgent.submitTask("Lance un build Maven"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Dispatcher indisponible");
        } finally {
            agentContextHolder.clear();
        }
    }

    /**
     * Configure le mock ChatClient pour simuler un appel au tool submit_task.
     *
     * @param delegatedDescription description transmise à submit_task
     * @param finalAnswer          réponse finale assistant après délégation
     */
    private void mockChatClientWithDelegation(String delegatedDescription, String finalAnswer) {
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(dispatcher.isRunning()).thenReturn(true);
        doAnswer(invocation -> {
            chatAgent.submitTask(delegatedDescription);
            return finalAnswer;
        }).when(callSpec).content();
    }

    /**
     * Configure le mock ChatClient pour une réponse textuelle simple sans appel outil.
     *
     * @param finalAnswer réponse finale assistant
     */
    private void mockChatClientWithoutDelegation(String finalAnswer) {
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(dispatcher.isRunning()).thenReturn(true);
        when(callSpec.content()).thenReturn(finalAnswer);
    }
}
