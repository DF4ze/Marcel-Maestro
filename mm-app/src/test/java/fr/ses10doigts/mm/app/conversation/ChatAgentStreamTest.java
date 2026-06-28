package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskRepository;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Tests unitaires du streaming token par token de {@link ChatAgent}.
 */
class ChatAgentStreamTest {

    @Test
    @DisplayName("stream retourne un flux non vide sur un ChatClient mocke")
    void stream_returnsNonEmptyFlux() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        MarcelChatPromptComposer promptComposer = mock(MarcelChatPromptComposer.class);
        TaskQueue taskQueue = mock(TaskQueue.class);
        ObjectProvider<Dispatcher> dispatcherProvider = emptyProvider();
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectWorkspaceRepository projectWorkspaceRepository = mock(ProjectWorkspaceRepository.class);
        PathValidator pathValidator = mock(PathValidator.class);
        ConversationTaskRepository conversationTaskRepository = mock(ConversationTaskRepository.class);
        AgentContextHolder agentContextHolder = new AgentContextHolder();

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(promptComposer.compose()).thenReturn("system");
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("Bon", "jour"));

        ChatAgent chatAgent = new ChatAgent(
                chatClient,
                chatMemory,
                promptComposer,
                taskQueue,
                agentContextHolder,
                dispatcherProvider,
                projectRepository,
                projectWorkspaceRepository,
                pathValidator,
                conversationTaskRepository);

        List<String> tokens = chatAgent.stream("conv-1", "Bonjour").collectList().block();

        assertThat(tokens).containsExactly("Bon", "jour");
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
