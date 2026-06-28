package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.specialist.coding.CodingAgentsProperties;
import fr.ses10doigts.mm.app.specialist.coding.TaskQualifier;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskStatus;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tests unitaires de persistance du lien conversation -> tache.
 */
class ConversationTaskPersistenceTest {

    @Test
    @DisplayName("submitTask persiste un ConversationTaskEntity RUNNING")
    void submitTask_persistsConversationTaskEntity() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        MarcelChatPromptComposer promptComposer = mock(MarcelChatPromptComposer.class);
        TaskQueue taskQueue = mock(TaskQueue.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectWorkspaceRepository projectWorkspaceRepository = mock(ProjectWorkspaceRepository.class);
        PathValidator pathValidator = mock(PathValidator.class);
        ConversationTaskRepository conversationTaskRepository = mock(ConversationTaskRepository.class);
        AgentContextHolder agentContextHolder = new AgentContextHolder();

        when(dispatcher.isRunning()).thenReturn(true);
        when(conversationTaskRepository.save(any(ConversationTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatAgent chatAgent = new ChatAgent(
                chatClient,
                chatMemory,
                promptComposer,
                taskQueue,
                agentContextHolder,
                fixedProvider(dispatcher),
                projectRepository,
                projectWorkspaceRepository,
                pathValidator,
                conversationTaskRepository,
                new TaskQualifier(new CodingAgentsProperties(), fixedProvider(null)),
                fixedProvider(null));

        agentContextHolder.bind(AgentContext.of("default", "project-1", "conversation-1", "chat-1"));
        try {
            String result = chatAgent.submitTask("Lance un build Maven");

            assertThat(result).contains("id: ");
            assertThat(result).contains("codex");
        } finally {
            agentContextHolder.clear();
        }

        ArgumentCaptor<ConversationTaskEntity> captor = ArgumentCaptor.forClass(ConversationTaskEntity.class);
        verify(conversationTaskRepository).save(captor.capture());
        ConversationTaskEntity persisted = captor.getValue();
        assertThat(persisted.getId()).isNotBlank();
        assertThat(persisted.getConversationId()).isEqualTo("conversation-1");
        assertThat(persisted.getTaskId()).isNotBlank();
        assertThat(persisted.getSubmittedAt()).isNotBlank();
        assertThat(persisted.getStatus()).isEqualTo(ConversationTaskStatus.RUNNING);
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
}
