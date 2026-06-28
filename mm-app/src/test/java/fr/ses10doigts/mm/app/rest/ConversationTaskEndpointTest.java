package fr.ses10doigts.mm.app.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.ses10doigts.mm.app.config.SyncAsyncTestConfiguration;
import fr.ses10doigts.mm.app.conversation.ConversationTitleService;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests d'integration du endpoint des taches de conversation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SyncAsyncTestConfiguration.class)
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ConversationTaskEndpointTest {

    @MockitoBean
    private ChatClient chatClient;

    @MockitoBean
    private TaskQueue taskQueue;

    @MockitoBean
    private Dispatcher dispatcher;

    @MockitoBean
    private ConversationTitleService titleService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private fr.ses10doigts.mm.app.conversation.ConversationService conversationService;

    @Autowired
    private fr.ses10doigts.mm.app.conversation.ChatAgent chatAgent;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /tasks retourne l'entree creee apres un submit_task")
    void getTasks_returnsPersistedConversationTask() throws Exception {
        ProjectEntity project = projectService.create("Projet Tasks");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        mockChatClientWithDelegation(
                "Lance un build Maven du projet courant",
                "Je lance ca, tu recevras une notification Telegram quand ce sera termine.");

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        project.getId(), conversation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Lance un build Maven du projet courant"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}/tasks",
                        project.getId(), conversation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationId").value(conversation.getId()))
                .andExpect(jsonPath("$[0].taskId").isNotEmpty())
                .andExpect(jsonPath("$[0].status").value("RUNNING"));
    }

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
}
