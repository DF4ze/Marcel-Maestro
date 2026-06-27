package fr.ses10doigts.mm.app.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.queue.InMemoryTaskQueue;
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
        Dispatcher dispatcher = mock(Dispatcher.class);
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TaskQueue taskQueue = new InMemoryTaskQueue();
        TelegramMmController controller = new TelegramMmController(
                fixedProvider(dispatcher),
                emptyProvider(),
                fixedProvider(conversationService),
                taskQueue,
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

        controller.chat(first);
        controller.chat(second);

        verify(conversationService).startConversation("project-1");
        verify(sessionService).setActiveConversationId(42L, "conv-1");
    }

    @Test
    @DisplayName("/reset puis message suivant demarre une nouvelle conversation")
    void reset_thenNextMessage_startsNewConversation() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TaskQueue taskQueue = new InMemoryTaskQueue();
        TelegramMmController controller = new TelegramMmController(
                fixedProvider(dispatcher),
                emptyProvider(),
                fixedProvider(conversationService),
                taskQueue,
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

        String response = controller.reset(resetCtx);
        controller.chat(messageCtx);

        assertThat(response).contains("Conversation");
        verify(sessionService).clearActiveConversationId(42L);
        verify(sessionService).setActiveConversationId(42L, "conv-2");
    }

    @Test
    @DisplayName("Deux chatIds differents gardent des conversations distinctes")
    void chat_differentChatIds_keepSeparateConversationIds() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TaskQueue taskQueue = new InMemoryTaskQueue();
        TelegramMmController controller = new TelegramMmController(
                fixedProvider(dispatcher),
                emptyProvider(),
                fixedProvider(conversationService),
                taskQueue,
                sessionService);

        TelegramUpdateContext chat1 = mock(TelegramUpdateContext.class);
        when(chat1.getText()).thenReturn("Message 1");
        when(chat1.getChatId()).thenReturn(1L);

        TelegramUpdateContext chat2 = mock(TelegramUpdateContext.class);
        when(chat2.getText()).thenReturn("Message 2");
        when(chat2.getChatId()).thenReturn(2L);

        when(sessionService.resolveProjectId(anyLong())).thenReturn(Optional.of("project-1"));
        when(sessionService.getActiveConversationId(1L)).thenReturn(Optional.empty());
        when(sessionService.getActiveConversationId(2L)).thenReturn(Optional.empty());
        when(conversationService.startConversation("project-1"))
                .thenReturn(ConversationEntity.builder().id("conv-1").projectId("project-1").build())
                .thenReturn(ConversationEntity.builder().id("conv-2").projectId("project-1").build());

        controller.chat(chat1);
        controller.chat(chat2);

        verify(sessionService).setActiveConversationId(1L, "conv-1");
        verify(sessionService).setActiveConversationId(2L, "conv-2");
        verify(conversationService, times(2)).startConversation("project-1");
    }

    @Test
    @DisplayName("Si une conversation active existe deja, aucune nouvelle conversation n'est creee")
    void chat_existingConversation_doesNotStartNewConversation() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        ConversationService conversationService = mock(ConversationService.class);
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TaskQueue taskQueue = new InMemoryTaskQueue();
        TelegramMmController controller = new TelegramMmController(
                fixedProvider(dispatcher),
                emptyProvider(),
                fixedProvider(conversationService),
                taskQueue,
                sessionService);

        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getText()).thenReturn("Suite");
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveProjectId(42L)).thenReturn(Optional.of("project-1"));
        when(sessionService.getActiveConversationId(42L)).thenReturn(Optional.of("conv-existing"));

        controller.chat(ctx);

        verify(conversationService, never()).startConversation("project-1");
        verify(sessionService, never()).setActiveConversationId(42L, "conv-existing");
    }

    @Test
    @DisplayName("/switch sans argument retourne une vue Telegram de selection")
    void switch_withoutArgs_returnsSelectionView() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                new InMemoryTaskQueue(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        when(ctx.getChatId()).thenReturn(42L);
        when(ctx.getArgs()).thenReturn(List.of());
        when(sessionService.findActiveProjectsByQuery("", 6)).thenReturn(List.of(
                ProjectEntity.builder().id("p1").name("Projet Alpha").build(),
                ProjectEntity.builder().id("p2").name("Projet Beta").build()
        ));

        Object result = controller.switchProject(ctx);

        assertThat(result).isInstanceOf(TelegramView.class);
        TelegramView view = (TelegramView) result;
        assertThat(view.getButtons()).hasSize(2);
        verify(sessionService).setSwitchSuggestions(42L, List.of("p1", "p2"));
    }

    @Test
    @DisplayName("/switch avec un seul match approximatif switch directement")
    void switch_withSingleApproximateMatch_switchesDirectly() {
        TelegramSessionService sessionService = mock(TelegramSessionService.class);
        TelegramMmController controller = new TelegramMmController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                new InMemoryTaskQueue(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        ProjectEntity project = ProjectEntity.builder().id("p1").name("Projet Alpha").build();
        when(ctx.getChatId()).thenReturn(42L);
        when(ctx.getArgs()).thenReturn(List.of("alph"));
        when(sessionService.findActiveProjectByName("alph")).thenReturn(Optional.empty());
        when(sessionService.findActiveProjectsByQuery("alph", 6)).thenReturn(List.of(project));
        when(sessionService.countOpenConversations("p1")).thenReturn(0L);

        Object result = controller.switchProject(ctx);

        assertThat(result).isEqualTo("✅ Projet actif : *Projet Alpha*\n0 conversations ouvertes");
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
                new InMemoryTaskQueue(),
                sessionService);
        TelegramUpdateContext ctx = mock(TelegramUpdateContext.class);
        ProjectEntity project = ProjectEntity.builder().id("p2").name("Projet Beta").build();
        when(ctx.getChatId()).thenReturn(42L);
        when(sessionService.resolveSwitchSuggestion(42L, 1)).thenReturn(Optional.of(project));
        when(sessionService.countOpenConversations("p2")).thenReturn(1L);

        String result = controller.onSwitch1(ctx);

        assertThat(result).isEqualTo("✅ Projet actif : *Projet Beta*\n1 conversation ouverte");
        verify(sessionService).setActiveProject(42L, "p2", "Projet Beta");
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
