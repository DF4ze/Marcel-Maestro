package fr.ses10doigts.mm.app.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.conversation.ConversationBriefService;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationStatus;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import fr.ses10doigts.telegrambots.model.TelegramUpdateContext;
import fr.ses10doigts.telegrambots.model.TelegramView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tests unitaires de {@link TelegramMmController}.
 */
class TelegramMmControllerTest {

    @Test
    @DisplayName("Deux messages successifs du meme chat reutilisent la meme conversation")
    void chat_sameChatId_reusesActiveConversationId() {
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                emptyProvider(),
                sessionService);

        TelegramUpdateContext first = mock(TelegramUpdateContext.class);
        when(first.getText()).thenReturn("Premier message");
        when(first.getChatId()).thenReturn(42L);

        TelegramUpdateContext second = mock(TelegramUpdateContext.class);
        when(second.getText()).thenReturn("Deuxieme message");
        when(second.getChatId()).thenReturn(42L);

        when(sessionService.resolveProjectId(42L)).thenReturn(Optional.of("project-1"));
        when(sessionService.getActiveConversationId(42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("conv-1"));
        when(conversationService.startConversation("project-1"))
                .thenReturn(ConversationEntity.builder().id("conv-1").projectId("project-1").build());
        when(conversationService.getConversation("conv-1"))
                .thenReturn(ConversationEntity.builder()
                        .id("conv-1")
                        .projectId("project-1")
                        .status(ConversationStatus.OPEN)
                        .build());
        when(conversationService.chat("conv-1", "Premier message")).thenReturn("Rep 1");
        when(conversationService.chat("conv-1", "Deuxieme message")).thenReturn("Rep 2");

        assertThat(controller.chat(first)).isEqualTo("Rep 1");
        assertThat(controller.chat(second)).isEqualTo("Rep 2");

        verify(conversationService).startConversation("project-1");
        verify(sessionService).setActiveConversationId(42L, "conv-1");
        verify(conversationService).chat("conv-1", "Premier message");
        verify(conversationService).chat("conv-1", "Deuxieme message");
    }

    @Test
    @DisplayName("/reset puis message suivant demarre une nouvelle conversation")
    void reset_thenNextMessage_startsNewConversation() {
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                emptyProvider(),
                sessionService);

        TelegramUpdateContext resetCtx = mock(TelegramUpdateContext.class);
        when(resetCtx.getChatId()).thenReturn(42L);

        TelegramUpdateContext messageCtx = mock(TelegramUpdateContext.class);
        when(messageCtx.getText()).thenReturn("Apres reset");
        when(messageCtx.getChatId()).thenReturn(42L);

        when(sessionService.resolveProjectId(42L)).thenReturn(Optional.of("project-1"));
        when(sessionService.getActiveConversationId(42L)).thenReturn(Optional.empty());
        when(conversationService.startConversation("project-1"))
                .thenReturn(ConversationEntity.builder().id("conv-2").projectId("project-1").build());
        when(conversationService.chat("conv-2", "Apres reset")).thenReturn("Rep reset");

        String response = controller.reset(resetCtx);

        assertThat(controller.chat(messageCtx)).isEqualTo("Rep reset");
        assertThat(response).contains("Conversation");
        verify(sessionService).clearActiveConversationId(42L);
        verify(sessionService).clearTransientState(42L);
        verify(sessionService).setActiveConversationId(42L, "conv-2");
        verify(conversationService).chat("conv-2", "Apres reset");
    }

    @Test
    @DisplayName("Deux chatIds differents gardent des conversations distinctes")
    void chat_differentChatIds_keepSeparateConversationIds() {
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                emptyProvider(),
                sessionService);

        TelegramUpdateContext chat1 = mock(TelegramUpdateContext.class);
        when(chat1.getText()).thenReturn("Message 1");
        when(chat1.getChatId()).thenReturn(1L);

        TelegramUpdateContext chat2 = mock(TelegramUpdateContext.class);
        when(chat2.getText()).thenReturn("Message 2");
        when(chat2.getChatId()).thenReturn(2L);

        when(sessionService.resolveProjectId(1L)).thenReturn(Optional.of("project-1"));
        when(sessionService.resolveProjectId(2L)).thenReturn(Optional.of("project-1"));
        when(sessionService.getActiveConversationId(1L)).thenReturn(Optional.empty());
        when(sessionService.getActiveConversationId(2L)).thenReturn(Optional.empty());
        when(conversationService.startConversation("project-1"))
                .thenReturn(ConversationEntity.builder().id("conv-1").projectId("project-1").build())
                .thenReturn(ConversationEntity.builder().id("conv-2").projectId("project-1").build());
        when(conversationService.chat("conv-1", "Message 1")).thenReturn("Rep 1");
        when(conversationService.chat("conv-2", "Message 2")).thenReturn("Rep 2");

        assertThat(controller.chat(chat1)).isEqualTo("Rep 1");
        assertThat(controller.chat(chat2)).isEqualTo("Rep 2");

        verify(sessionService).setActiveConversationId(1L, "conv-1");
        verify(sessionService).setActiveConversationId(2L, "conv-2");
        verify(conversationService, times(2)).startConversation("project-1");
        verify(conversationService).chat("conv-1", "Message 1");
        verify(conversationService).chat("conv-2", "Message 2");
    }

    @Test
    @DisplayName("Si une conversation active existe deja, aucune nouvelle conversation n'est creee")
    void chat_existingConversation_doesNotStartNewConversation() {
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                emptyProvider(),
                sessionService);

        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getText()).thenReturn("Suite");
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveProjectId(42L)).thenReturn(Optional.of("project-1"));
        when(sessionService.getActiveConversationId(42L)).thenReturn(Optional.of("conv-existing"));
        when(conversationService.getConversation("conv-existing"))
                .thenReturn(ConversationEntity.builder()
                        .id("conv-existing")
                        .projectId("project-1")
                        .status(ConversationStatus.OPEN)
                        .build());
        when(conversationService.chat("conv-existing", "Suite")).thenReturn("Rep suite");

        assertThat(controller.chat(ctx)).isEqualTo("Rep suite");

        verify(conversationService, never()).startConversation("project-1");
        verify(sessionService, never()).setActiveConversationId(42L, "conv-existing");
        verify(conversationService).chat("conv-existing", "Suite");
    }

    @Test
    @DisplayName("Un message commencant par / n'est jamais envoye au flux conversationnel")
    void chat_slashMessage_isIntercepted() {
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                emptyProvider(),
                sessionService);

        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getText()).thenReturn("/conversations");
        when(ctx.getChatId()).thenReturn(42L);

        String response = controller.chat(ctx);

        assertThat(response).contains("Commande inconnue");
        verify(sessionService, never()).resolveProjectId(42L);
        verify(conversationService, never()).startConversation(anyString());
        verify(conversationService, never()).chat(anyString(), anyString());
    }

    @Test
    @DisplayName("Une conversation active archivee est refusee et nettoyee")
    void chat_archivedActiveConversation_isBlocked() {
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                emptyProvider(),
                sessionService);

        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getText()).thenReturn("Message libre");
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveProjectId(42L)).thenReturn(Optional.of("project-1"));
        when(sessionService.getActiveConversationId(42L)).thenReturn(Optional.of("conv-archived"));
        when(conversationService.getConversation("conv-archived"))
                .thenReturn(ConversationEntity.builder()
                        .id("conv-archived")
                        .projectId("project-1")
                        .status(ConversationStatus.ARCHIVED)
                        .build());

        String response = controller.chat(ctx);

        assertThat(response).contains("archivee");
        verify(sessionService).clearActiveConversationId(42L);
        verify(sessionService).clearConversationSuggestions(42L);
        verify(conversationService, never()).chat(anyString(), anyString());
    }

    @Test
    @DisplayName("Si ConversationService est absent, le chat Telegram retourne une erreur explicite")
    void chat_withoutConversationService_returnsExplicitError() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);

        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getText()).thenReturn("Bonjour");
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveProjectId(42L)).thenReturn(Optional.of("project-1"));

        assertThat(controller.chat(ctx)).isEqualTo("ConversationService non disponible.");
    }

    @Test
    @DisplayName("/new cree explicitement une nouvelle conversation")
    void newConversation_createsAndActivatesConversation() {
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveProjectId(42L)).thenReturn(Optional.of("project-1"));
        when(conversationService.startConversation("project-1"))
                .thenReturn(ConversationEntity.builder().id("conv-new").projectId("project-1").build());

        String response = controller.newConversation(ctx);

        assertThat(response).contains("Nouvelle conversation");
        verify(sessionService).setActiveConversationId(42L, "conv-new");
        verify(sessionService).clearConversationSuggestions(42L);
    }

    @Test
    @DisplayName("/brief retourne le brief de la conversation active")
    void brief_returnsSummaryForActiveConversation() {
        ConversationBriefService briefService = mock(ConversationBriefService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                fixedProvider(briefService),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.getActiveConversationId(42L)).thenReturn(Optional.of("conv-brief"));
        when(briefService.brief("conv-brief")).thenReturn("Objectif : cadrer");

        String response = controller.brief(ctx);

        assertThat(response).isEqualTo("Brief courant :\nObjectif : cadrer");
    }

    @Test
    @DisplayName("/brief sans conversation active retourne un message explicite")
    void brief_withoutActiveConversation_returnsReadableMessage() {
        ConversationBriefService briefService = mock(ConversationBriefService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                fixedProvider(briefService),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.getActiveConversationId(42L)).thenReturn(Optional.empty());

        String response = controller.brief(ctx);

        assertThat(response).contains("Aucune conversation active");
    }

    @Test
    @DisplayName("/conversations retourne un menu de selection de projet")
    void conversations_returnsProjectSelectionView() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.listActiveProjectsByRecentActivity()).thenReturn(List.of(
                ProjectEntity.builder().id("project-1").name("Projet Alpha").build(),
                ProjectEntity.builder().id("project-2").name("Projet Beta").build()));

        Object response = controller.conversations(ctx);

        assertThat(response).isInstanceOf(TelegramView.class);
        verify(sessionService).openNavigation(
                42L,
                TelegramSessionService.NavigationIntent.BROWSE_CONVERSATIONS,
                List.of("project-1", "project-2"));
    }

    @Test
    @DisplayName("Selection projet /conversations retourne les conversations de ce projet")
    void conversationsProjectSelection_listsConversations() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveConversationProjectSuggestion(42L, 0)).thenReturn(Optional.of(
                ProjectEntity.builder().id("project-1").name("Projet Alpha").build()));
        when(sessionService.listOpenConversationsForProject("project-1", 5)).thenReturn(List.of(
                ConversationEntity.builder().id("c1").title("Analyse module").startedAt("2026-06-28T10:00:00Z").build(),
                ConversationEntity.builder().id("c2").title("Bug VPS").startedAt("2026-06-27T10:00:00Z").build()
        ));

        Object response = controller.onConversationsProject0(ctx);

        assertThat(response).isInstanceOf(String.class);
        assertThat((String) response).contains("Conversations - Projet Alpha");
        assertThat((String) response).contains("1. Analyse module");
        verify(sessionService).setConversationSuggestions(42L, List.of("c1", "c2"));
    }

    @Test
    @DisplayName("/conv avec suggestion valide active la conversation")
    void conv_validSuggestion_activatesConversation() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(ctx.getArgs()).thenReturn(List.of("1"));
        when(sessionService.resolveConversationSuggestion(42L, 0)).thenReturn(Optional.of(
                ConversationEntity.builder().id("conv-1").title("Analyse module").status(ConversationStatus.OPEN).build()
        ));

        String response = controller.conversation(ctx);

        assertThat(response).contains("Conversation reprise");
        verify(sessionService).setActiveConversationId(42L, "conv-1");
    }

    @Test
    @DisplayName("/delete project retourne une vue Telegram de selection")
    void deleteProject_returnsSelectionView() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(ctx.getArgs()).thenReturn(List.of("project"));
        when(sessionService.listActiveProjectsByRecentActivity()).thenReturn(List.of(
                ProjectEntity.builder().id("p1").name("Projet Alpha").status(ProjectStatus.ACTIVE).build(),
                ProjectEntity.builder().id("p2").name("Projet Beta").status(ProjectStatus.ACTIVE).build()
        ));

        Object result = controller.delete(ctx);

        assertThat(result).isInstanceOf(TelegramView.class);
        verify(sessionService).openNavigation(
                42L,
                TelegramSessionService.NavigationIntent.DELETE,
                List.of("p1", "p2"));
    }

    @Test
    @DisplayName("Selection delete project prepare une confirmation")
    void deleteProjectSelection_preparesConfirmation() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveDeleteProjectSuggestion(42L, 0)).thenReturn(Optional.of(
                ProjectEntity.builder().id("p1").name("Projet Alpha").status(ProjectStatus.ACTIVE).build()
        ));

        Object result = controller.onDeleteProject0(ctx);

        assertThat(result).isInstanceOf(TelegramView.class);
        verify(sessionService).setPendingDeleteConfirmation(42L,
                new TelegramSessionService.PendingAction(
                        TelegramSessionService.PendingActionType.DELETE_PROJECT,
                        "p1",
                        "Projet Alpha"));
    }

    @Test
    @DisplayName("Confirmation delete project supprime le projet et nettoie la session active")
    void confirmDeleteProject_deletesAndClearsActiveSession() {
        ProjectService projectService = mock(ProjectService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                fixedProvider(projectService),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.getPendingDeleteConfirmation(42L)).thenReturn(Optional.of(
                new TelegramSessionService.PendingAction(
                        TelegramSessionService.PendingActionType.DELETE_PROJECT,
                        "p1",
                        "Projet Alpha")));
        when(sessionService.getActiveProjectId(42L)).thenReturn(Optional.of("p1"));

        String response = controller.onConfirmDelete(ctx);

        assertThat(response).contains("Action : suppression");
        assertThat(response).contains("Projet : Projet Alpha");
        verify(projectService).delete("p1");
        verify(sessionService).clearTransientState(42L);
        verify(sessionService).clearActiveProject(42L);
    }

    @Test
    @DisplayName("Un texte libre peut servir de raison d'archivage de conversation")
    void chat_pendingArchiveReason_archivesConversation() {
        ConversationService conversationService = mock(ConversationService.class);
        ProjectService projectService = mock(ProjectService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                fixedProvider(conversationService),
                emptyProvider(),
                fixedProvider(projectService),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(ctx.getText()).thenReturn("Plus utile");
        when(sessionService.getPendingArchiveReason(42L)).thenReturn(Optional.of(
                new TelegramSessionService.PendingAction(
                        TelegramSessionService.PendingActionType.ARCHIVE_CONVERSATION,
                        "conv-1",
                        "Analyse module")));
        when(sessionService.getActiveConversationId(42L)).thenReturn(Optional.of("conv-1"));
        when(conversationService.getConversation("conv-1")).thenReturn(
                ConversationEntity.builder().id("conv-1").projectId("project-1").title("Analyse module").build());
        when(projectService.findById("project-1")).thenReturn(
                ProjectEntity.builder().id("project-1").name("Projet Alpha").build());

        String response = controller.chat(ctx);

        assertThat(response).contains("Action : archivage");
        assertThat(response).contains("Projet : Projet Alpha");
        assertThat(response).contains("Conversation : Analyse module");
        verify(sessionService).clearTransientState(42L);
        verify(conversationService).archive("conv-1", "Plus utile");
        verify(sessionService).clearActiveConversationId(42L);
        verify(conversationService, never()).chat(anyString(), anyString());
    }

    @Test
    @DisplayName("/switch sans argument retourne une vue Telegram de selection")
    void switch_withoutArgs_returnsSelectionView() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(ctx.getArgs()).thenReturn(List.of());
        when(sessionService.listActiveProjectsByRecentActivity()).thenReturn(List.of(
                ProjectEntity.builder().id("p1").name("Projet Alpha").build(),
                ProjectEntity.builder().id("p2").name("Projet Beta").build()
        ));

        Object result = controller.switchProject(ctx);

        assertThat(result).isInstanceOf(TelegramView.class);
        TelegramView view = (TelegramView) result;
        assertThat(view.getButtons()).hasSize(3);
        verify(sessionService).openNavigation(
                42L,
                TelegramSessionService.NavigationIntent.SWITCH,
                List.of("p1", "p2"));
    }

    @Test
    @DisplayName("/switch avec un seul match approximatif switch directement")
    void switch_withSingleApproximateMatch_switchesDirectly() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        ProjectEntity project = ProjectEntity.builder().id("p1").name("Projet Alpha").build();
        when(ctx.getChatId()).thenReturn(42L);
        when(ctx.getArgs()).thenReturn(List.of("alph"));
        when(sessionService.findActiveProjectByName("alph")).thenReturn(Optional.empty());
        when(sessionService.findActiveProjectsByQuery("alph", 20)).thenReturn(List.of(project));
        when(sessionService.countOpenConversations("p1")).thenReturn(0L);

        Object result = controller.switchProject(ctx);

        assertThat(result).isEqualTo(
                "Projet actif : *Projet Alpha*\n0 conversations ouvertes\nLa conversation sera creee au premier message.");
        verify(sessionService).setActiveProject(42L, "p1", "Projet Alpha");
    }

    @Test
    @DisplayName("Callback de suggestion /switch active le projet selectionne")
    void switch_callback_activatesSuggestedProject() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        ProjectEntity project = ProjectEntity.builder().id("p2").name("Projet Beta").build();
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveSwitchSuggestion(42L, 1)).thenReturn(Optional.of(project));
        when(sessionService.countOpenConversations("p2")).thenReturn(1L);

        String result = controller.onSwitch1(ctx);

        assertThat(result).isEqualTo(
                "Projet actif : *Projet Beta*\n1 conversation ouverte\nLa conversation sera creee au premier message.");
        verify(sessionService).setActiveProject(42L, "p2", "Projet Beta");
    }

    @Test
    @DisplayName("Selection projet en navigation affiche les actions projet et la liste des conversations")
    void navigationProjectSelection_returnsProjectDetailView() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveNavigationProjectSuggestion(42L, 0)).thenReturn(Optional.of(
                ProjectEntity.builder().id("project-1").name("Projet Alpha").build()));
        when(sessionService.listOpenConversationsForProject("project-1", 20)).thenReturn(List.of(
                ConversationEntity.builder().id("c1").title("Analyse module").startedAt("2026-06-28T10:00:00Z").build()
        ));
        when(sessionService.getActiveProjectId(42L)).thenReturn(Optional.of("project-1"));

        Object response = controller.onNavProject0(ctx);

        assertThat(response).isInstanceOf(TelegramView.class);
        TelegramView view = (TelegramView) response;
        assertThat(view.getText()).contains("Projet : Projet Alpha");
        assertThat(view.getText()).contains("Projet actif dans cette session");
        assertThat(view.getButtons().getFirst()).extracting("text")
                .containsExactly("🔀 Switcher", "📦 Archiver", "🗑️ Supprimer");
        verify(sessionService).selectNavigationProject(42L, "project-1", List.of("c1"));
        verify(sessionService).setConversationSuggestions(42L, List.of("c1"));
    }

    private static <T> ObjectProvider<T> fixedProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return null;
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }

            @Override
            public T getObject() {
                return null;
            }
        };
    }
}
