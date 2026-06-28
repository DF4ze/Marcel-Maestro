package fr.ses10doigts.mm.app.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.ses10doigts.mm.app.config.ScriptedChatClientTestConfiguration;
import fr.ses10doigts.mm.app.config.SyncAsyncTestConfiguration;
import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.conversation.ConversationTitleService;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.app.support.ScriptedChatModel;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests d'integration SSE du controleur de conversation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SyncAsyncTestConfiguration.class, ScriptedChatClientTestConfiguration.class})
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ConversationSseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ScriptedChatModel scriptedChatModel;

    @MockitoBean
    private ConversationTitleService titleService;

    @Test
    @DisplayName("POST /messages avec Accept text/event-stream retourne un flux SSE et un evenement done")
    void postMessages_streamsSseAndDoneEvent() throws Exception {
        ProjectEntity project = projectService.create("Projet REST SSE");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Bonjour SSE");

        MvcResult started = mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        project.getId(), conversation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"content":"Bonjour Marcel"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpServletResponse response = completed.getResponse();
        assertThat(response.getContentType()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getContentAsString()).contains("data:B");
        assertThat(response.getContentAsString()).contains("[DONE]");
    }
}
