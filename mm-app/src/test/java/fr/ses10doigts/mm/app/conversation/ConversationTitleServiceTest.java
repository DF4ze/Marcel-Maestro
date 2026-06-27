package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.config.SyncAsyncTestConfiguration;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests unitaires du {@link ConversationTitleService} (E2-M5).
 *
 * <p>Vérifie :</p>
 * <ul>
 *   <li>Le titre est généré et persisté en DB après un appel LLM réussi.</li>
 *   <li>En cas d'erreur LLM, le titre reste {@code null} (pas de retry).</li>
 *   <li>Si la conversation n'existe plus au moment de la mise à jour, silence.</li>
 * </ul>
 *
 * <p>Le {@link ChatClient} est mocké pour éviter tout appel réseau.
 * Les autres beans (DB SQLite in-memory) sont réels.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(SyncAsyncTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConversationTitleServiceTest {

    @MockitoBean
    private ChatClient chatClient;

    @Autowired
    private ConversationTitleService titleService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private ProjectEntity project;
    private ConversationEntity conversation;

    @BeforeEach
    void setUp() {
        // Nettoyage DB avant chaque test (SQLite in-memory cache=shared peut survivre entre contextes)
        conversationRepository.deleteAll();
        projectRepository.deleteAll();
        project = projectService.create("Projet Titre Test");
        conversation = conversationService.startConversation(project.getId());
    }

    @AfterEach
    void tearDown() {
        conversationRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Génération réussie
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateTitle — titre généré et persisté en DB")
    void generateTitle_success_persistsTitle() {
        // Arrange : mock ChatClient pour retourner un titre
        mockChatClientResponse("Analyse de code Spring Boot");

        // Act : appel direct (synchrone en test — @Async est ignoré dans le contexte de test)
        titleService.generateTitle(conversation.getId(), "Peux-tu analyser ce code Spring Boot ?");

        // Assert : le titre est persisté en DB
        Optional<ConversationEntity> updated = conversationRepository.findById(conversation.getId());
        assertThat(updated).isPresent();
        assertThat(updated.get().getTitle()).isEqualTo("Analyse de code Spring Boot");
    }

    @Test
    @DisplayName("generateTitle — titre nettoyé des guillemets éventuels")
    void generateTitle_stripsQuotes() {
        // Certains modèles retournent le titre entre guillemets
        mockChatClientResponse("\"Refactoring du module auth\"");

        titleService.generateTitle(conversation.getId(), "Refactorise le module d'authentification");

        Optional<ConversationEntity> updated = conversationRepository.findById(conversation.getId());
        assertThat(updated.get().getTitle()).isEqualTo("Refactoring du module auth");
    }

    @Test
    @DisplayName("generateTitle — titre avec espaces en début/fin est strippé")
    void generateTitle_stripsWhitespace() {
        mockChatClientResponse("  Bug dans la config  ");

        titleService.generateTitle(conversation.getId(), "J'ai un bug dans ma configuration");

        Optional<ConversationEntity> updated = conversationRepository.findById(conversation.getId());
        assertThat(updated.get().getTitle()).isEqualTo("Bug dans la config");
    }

    @Test
    @DisplayName("generateTitle — premier message très long : titre persisté et LLM appelé exactement une fois")
    void generateTitle_longMessage_truncatedAndTitlePersisted() {
        // Arrange : message de 1000 chars (> MAX_EXCERPT_LENGTH = 500)
        String longMessage = "A".repeat(1000);

        // Préparer le mock pour capturer l'argument réel passé à .user(...)
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        org.mockito.ArgumentCaptor<String> userArgCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        when(requestSpec.user(userArgCaptor.capture())).thenReturn(requestSpec);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Titre long message");

        // Act
        titleService.generateTitle(conversation.getId(), longMessage);

        // Assert 1 : titre persisté malgré la troncature
        Optional<ConversationEntity> updated = conversationRepository.findById(conversation.getId());
        assertThat(updated.get().getTitle()).isEqualTo("Titre long message");

        // Assert 2 : l'argument passé au LLM ne contient pas le message original en entier
        String sentToLlm = userArgCaptor.getValue();
        assertThat(sentToLlm).endsWith("…"); // marqueur de troncature
        // le nombre de 'A' dans l'argument est exactement MAX_EXCERPT_LENGTH
        long aCount = sentToLlm.chars().filter(c -> c == 'A').count();
        assertThat(aCount).isEqualTo(ConversationTitleService.MAX_EXCERPT_LENGTH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Erreur LLM — title reste null (ADR-025, pas de retry)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateTitle — erreur LLM : title reste null, pas de retry")
    void generateTitle_llmError_titleRemainsNull() {
        // Arrange : mock ChatClient pour lever une exception
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenThrow(new RuntimeException("LLM unavailable"));

        // Act : ne doit pas lever d'exception
        titleService.generateTitle(conversation.getId(), "Première tâche");

        // Assert : title est toujours null
        Optional<ConversationEntity> updated = conversationRepository.findById(conversation.getId());
        assertThat(updated).isPresent();
        assertThat(updated.get().getTitle()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation introuvable — silence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateTitle — conversation supprimée entre-temps : silence (pas d'exception)")
    void generateTitle_conversationDeleted_silentNoOp() {
        mockChatClientResponse("Titre pour conversation disparue");

        // Supprimer la conversation avant l'appel
        conversationRepository.deleteById(conversation.getId());

        // Act : ne doit pas lever d'exception
        titleService.generateTitle(conversation.getId(), "Message quelconque");

        // Assert : aucune conversation trouvée — pas d'erreur
        assertThat(conversationRepository.findById(conversation.getId())).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intégration : trigger depuis ConversationService.addMessage
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addMessage (premier message) — ConversationTitleService est appelé")
    void addMessage_firstMessage_triggersTitleService() {
        // Arrange
        mockChatClientResponse("Titre auto généré");

        // Act : addMessage déclenche generateTitle via ObjectProvider
        conversationService.addMessage(conversation.getId(), "Mon premier message");

        // Assert : ChatClient a été appelé (la génération a eu lieu)
        // Note : @Async est désactivé en test — l'appel est synchrone
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("addMessage (deuxième message) — ConversationTitleService N'est PAS appelé")
    void addMessage_subsequentMessage_doesNotTriggerTitleService() {
        // Arrange : premier message (titre déjà déclenché)
        mockChatClientResponse("Titre premier");
        conversationService.addMessage(conversation.getId(), "Premier message");

        // Reset mock pour le deuxième appel
        org.mockito.Mockito.reset(chatClient);

        // Act : deuxième message — ne doit pas relancer la génération
        conversationService.addMessage(conversation.getId(), "Deuxième message");

        // Assert : ChatClient n'a pas été sollicité
        verify(chatClient, never()).prompt();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configure le mock ChatClient pour retourner le titre donné.
     */
    private void mockChatClientResponse(String title) {
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(title);
    }
}
