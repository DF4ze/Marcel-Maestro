package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.project.ProjectBootstrapService;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;

class ConversationServiceBootstrapTest {

    @Test
    @DisplayName("startConversation marque la première conversation comme cadrage initial")
    void startConversation_firstConversation_setsBootstrapTitle() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatAgent chatAgent = mock(ChatAgent.class);
        AgentContextHolder contextHolder = new AgentContextHolder();
        ProjectBootstrapService bootstrapService = mock(ProjectBootstrapService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConversationTitleService> titleProvider = mock(ObjectProvider.class);

        ProjectEntity project = ProjectEntity.builder()
                .id("project-a")
                .status(ProjectStatus.ACTIVE)
                .build();
        when(projectRepository.findById("project-a")).thenReturn(Optional.of(project));
        when(conversationRepository.findAllByProjectId("project-a")).thenReturn(List.of());
        when(conversationRepository.save(any(ConversationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConversationService service = new ConversationService(
                conversationRepository,
                projectRepository,
                chatMemory,
                chatAgent,
                contextHolder,
                bootstrapService,
                titleProvider);

        ConversationEntity created = service.startConversation("project-a");

        assertThat(created.getTitle()).isEqualTo(ProjectBootstrapService.BOOTSTRAP_CONVERSATION_TITLE);
        verify(bootstrapService).initializeBootstrapConversation(project, created.getId());
    }

    @Test
    @DisplayName("chat enregistre le message utilisateur dans PROJECT.md avant l'appel LLM pour la conversation initiale")
    void chat_bootstrapConversation_appendsProjectInput() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatAgent chatAgent = mock(ChatAgent.class);
        AgentContextHolder contextHolder = new AgentContextHolder();
        ProjectBootstrapService bootstrapService = mock(ProjectBootstrapService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConversationTitleService> titleProvider = mock(ObjectProvider.class);
        when(titleProvider.getIfAvailable()).thenReturn(null);

        ConversationEntity conversation = ConversationEntity.builder()
                .id("conv-1")
                .projectId("project-a")
                .build();
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation));
        when(chatMemory.get("conv-1")).thenReturn(List.of());
        when(chatAgent.chat("conv-1", "Voici le besoin du projet")).thenReturn("Question suivante");

        ConversationService service = new ConversationService(
                conversationRepository,
                projectRepository,
                chatMemory,
                chatAgent,
                contextHolder,
                bootstrapService,
                titleProvider);

        String response = service.chat("conv-1", "Voici le besoin du projet");

        assertThat(response).isEqualTo("Question suivante");
        verify(bootstrapService).appendUserInputToProject("project-a", "conv-1", "Voici le besoin du projet");
    }

    @Test
    @DisplayName("startConversation n'active pas le cadrage initial si le projet a déjà une conversation")
    void startConversation_nonFirstConversation_doesNotBootstrap() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatAgent chatAgent = mock(ChatAgent.class);
        AgentContextHolder contextHolder = new AgentContextHolder();
        ProjectBootstrapService bootstrapService = mock(ProjectBootstrapService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConversationTitleService> titleProvider = mock(ObjectProvider.class);

        ProjectEntity project = ProjectEntity.builder()
                .id("project-b")
                .status(ProjectStatus.ACTIVE)
                .build();
        when(projectRepository.findById("project-b")).thenReturn(Optional.of(project));
        when(conversationRepository.findAllByProjectId("project-b"))
                .thenReturn(List.of(ConversationEntity.builder().id("conv-old").projectId("project-b").build()));
        when(conversationRepository.save(any(ConversationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConversationService service = new ConversationService(
                conversationRepository,
                projectRepository,
                chatMemory,
                chatAgent,
                contextHolder,
                bootstrapService,
                titleProvider);

        ConversationEntity created = service.startConversation("project-b");

        assertThat(created.getTitle()).isNull();
        verify(bootstrapService, never()).initializeBootstrapConversation(any(), any());
    }
}
