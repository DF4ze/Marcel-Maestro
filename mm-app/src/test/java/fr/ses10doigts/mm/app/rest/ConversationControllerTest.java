package fr.ses10doigts.mm.app.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.ses10doigts.mm.app.config.ScriptedChatClientTestConfiguration;
import fr.ses10doigts.mm.app.config.SyncAsyncTestConfiguration;
import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.conversation.ConversationTitleService;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.app.support.ScriptedChatModel;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * Tests HTTP E3-M1 du contrôleur de conversation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SyncAsyncTestConfiguration.class, ScriptedChatClientTestConfiguration.class})
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
class ConversationControllerTest {

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
    @DisplayName("POST /messages — retourne 200 et la réponse assistant")
    void postMessages_returnsAssistantResponse() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Chat");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Bonjour ! Comment puis-je t'aider ?");

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        project.getId(), conversation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Bonjour Marcel"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.content").value("Bonjour ! Comment puis-je t'aider ?"));
    }

    @Test
    @DisplayName("GET /messages — retourne toujours l'historique complet USER + ASSISTANT")
    void getMessages_returnsFullHistoryAfterChat() throws Exception {
        ProjectEntity project = projectService.create("Projet REST History");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Réponse HTTP");
        conversationService.chat(conversation.getId(), "Question HTTP");

        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}/messages",
                        project.getId(), conversation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("Question HTTP"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].content").value("Réponse HTTP"));
    }
}
