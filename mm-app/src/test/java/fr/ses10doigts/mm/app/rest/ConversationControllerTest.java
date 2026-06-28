package fr.ses10doigts.mm.app.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests HTTP du controleur de conversation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SyncAsyncTestConfiguration.class, ScriptedChatClientTestConfiguration.class})
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
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
    @DisplayName("GET /conversations retourne messageCount et lastMessageAt")
    void listConversations_returnsActivityFields() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Liste");
        ConversationEntity conversation = conversationService.startConversation(project.getId());

        conversationService.addMessage(conversation.getId(), "Bonjour REST");

        mockMvc.perform(get("/projects/{projectId}/conversations", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(conversation.getId()))
                .andExpect(jsonPath("$[0].messageCount").value(1))
                .andExpect(jsonPath("$[0].lastMessageAt").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /conversations/{id} renomme une conversation")
    void patchConversation_updatesTitle() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Rename");
        ConversationEntity conversation = conversationService.startConversation(project.getId());

        mockMvc.perform(patch("/projects/{projectId}/conversations/{conversationId}",
                        project.getId(), conversation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renommee via HTTP"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renommee via HTTP"));
    }

    @Test
    @DisplayName("PATCH /conversations/{id} retourne 400 si le titre est vide")
    void patchConversation_blankTitle_returnsBadRequest() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Rename Ko");
        ConversationEntity conversation = conversationService.startConversation(project.getId());

        mockMvc.perform(patch("/projects/{projectId}/conversations/{conversationId}",
                        project.getId(), conversation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /conversations/{id}/archive archive puis filtre la conversation")
    void archiveConversation_thenFilterByStatus() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Archive");
        ConversationEntity conversation = conversationService.startConversation(project.getId());

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/archive",
                        project.getId(), conversation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/archive",
                        project.getId(), conversation.getId()))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/projects/{projectId}/conversations?status=OPEN", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/projects/{projectId}/conversations?status=ARCHIVED", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(conversation.getId()))
                .andExpect(jsonPath("$[0].status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("POST /messages retourne 409 sur conversation archivee")
    void postMessages_onArchivedConversation_returnsConflict() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Archived Message");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        conversationService.archive(conversation.getId());

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        project.getId(), conversation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Message interdit"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /messages retourne 200 et la reponse assistant")
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
    @DisplayName("GET /messages retourne toujours l'historique complet USER + ASSISTANT")
    void getMessages_returnsFullHistoryAfterChat() throws Exception {
        ProjectEntity project = projectService.create("Projet REST History");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Reponse HTTP");
        conversationService.chat(conversation.getId(), "Question HTTP");

        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}/messages",
                        project.getId(), conversation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("Question HTTP"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].content").value("Reponse HTTP"));
    }

    @Test
    @DisplayName("GET /brief retourne un resume de la conversation")
    void getBrief_returnsConversationSummary() throws Exception {
        ProjectEntity project = projectService.create("Projet REST Brief");
        ConversationEntity conversation = conversationService.startConversation(project.getId());
        scriptedChatModel.reply("Reponse 1");
        scriptedChatModel.reply("Objectif: resumer la conversation");

        conversationService.chat(conversation.getId(), "Question de contexte");

        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}/brief",
                        project.getId(), conversation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversation.getId()))
                .andExpect(jsonPath("$.brief").value("Objectif: resumer la conversation"));
    }
}
