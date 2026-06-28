package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.specialist.coding.TaskQualifier;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.journal.Journal;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.core.tool.WorkspaceRegistry;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskRepository;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceEntity;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class ChatAgentReadProjectFileTest {

    private final AgentContextHolder contextHolder = new AgentContextHolder();
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectWorkspaceRepository projectWorkspaceRepository = mock(ProjectWorkspaceRepository.class);

    @AfterEach
    void tearDown() {
        contextHolder.clear();
    }

    @Test
    @DisplayName("Fichier existant - le contenu est retourne")
    void readProjectFile_withExistingFile_returnsContent(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-a"));
        Files.createDirectories(projectWorkspace.resolve("src"));
        Files.writeString(projectWorkspace.resolve("src/Main.java"), "class Main {}", StandardCharsets.UTF_8);
        ChatAgent chatAgent = newChatAgent(workspaceRoot, projectWorkspace, 5000, "project-a");

        String content = chatAgent.readProjectFile("src/Main.java");

        assertThat(content).isEqualTo("class Main {}");
    }

    @Test
    @DisplayName("Fichier inexistant - message lisible retourne")
    void readProjectFile_withMissingFile_returnsReadableMessage(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-b"));
        ChatAgent chatAgent = newChatAgent(workspaceRoot, projectWorkspace, 5000, "project-b");

        String content = chatAgent.readProjectFile("src/Missing.java");

        assertThat(content).contains("Fichier introuvable");
        assertThat(content).contains("src/Missing.java");
    }

    @Test
    @DisplayName("Path traversal - acces refuse")
    void readProjectFile_withTraversal_rejectsPath(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-c"));
        ChatAgent chatAgent = newChatAgent(workspaceRoot, projectWorkspace, 5000, "project-c");

        String content = chatAgent.readProjectFile("../../Windows/System32/cmd.exe");

        assertThat(content).contains("Acces refuse");
    }

    @Test
    @DisplayName("Fichier long - contenu tronque a la limite")
    void readProjectFile_withLongContent_truncates(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-d"));
        Files.createDirectories(projectWorkspace.resolve("notes"));
        Files.writeString(projectWorkspace.resolve("notes/todo.md"), "b".repeat(40), StandardCharsets.UTF_8);
        ChatAgent chatAgent = newChatAgent(workspaceRoot, projectWorkspace, 20, "project-d");

        String content = chatAgent.readProjectFile("notes/todo.md");

        assertThat(content).startsWith("b".repeat(20));
        assertThat(content).contains("[... contenu tronque]");
        assertThat(content).doesNotContain("b".repeat(40));
    }

    @Test
    @DisplayName("Fichier absent du workspace interne - fallback vers un workspace rattache")
    void readProjectFile_withAttachedWorkspaceFallback_returnsContent(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-e"));
        Path externalWorkspace = Files.createDirectories(tempDir.resolve("repo-externe"));
        Files.createDirectories(externalWorkspace.resolve("src"));
        Files.writeString(externalWorkspace.resolve("src/Main.java"), "class External {}", StandardCharsets.UTF_8);
        when(projectWorkspaceRepository.findAllByProjectId("project-e")).thenReturn(List.of(
                ProjectWorkspaceEntity.builder().id("ws-1").path(externalWorkspace.toString()).build()));
        ChatAgent chatAgent = newChatAgent(workspaceRoot, projectWorkspace, 5000, "project-e", externalWorkspace);

        String content = chatAgent.readProjectFile("src/Main.java");

        assertThat(content).isEqualTo("class External {}");
    }

    @Test
    @DisplayName("Chemin sortant du workspace principal mais ciblant un workspace rattache - contenu retourne")
    void readProjectFile_withParentTraversalIntoAttachedWorkspace_returnsContent(@TempDir Path tempDir)
            throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-f"));
        Path attachedWorkspace = workspaceRoot.getParent();
        Files.writeString(attachedWorkspace.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        when(projectWorkspaceRepository.findAllByProjectId("project-f")).thenReturn(List.of(
                ProjectWorkspaceEntity.builder().id("ws-1").path(attachedWorkspace.toString()).build()));
        ChatAgent chatAgent = newChatAgent(workspaceRoot, projectWorkspace, 5000, "project-f", attachedWorkspace);

        String content = chatAgent.readProjectFile("../../pom.xml");

        assertThat(content).isEqualTo("<project/>");
    }

    private ChatAgent newChatAgent(Path workspaceRoot, Path projectWorkspace, int maxChars, String projectId) {
        return newChatAgent(workspaceRoot, projectWorkspace, maxChars, projectId, null);
    }

    private ChatAgent newChatAgent(Path workspaceRoot, Path projectWorkspace, int maxChars, String projectId,
                                   Path externalWorkspace) {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ProjectEntity.builder()
                .id(projectId)
                .name("Projet " + projectId)
                .workspacePath(projectWorkspace.toString())
                .build()));
        contextHolder.bind(AgentContext.of("default", projectId, "conv-1", "task-1"));

        @SuppressWarnings("unchecked")
        ObjectProvider<Dispatcher> dispatcherProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Journal> journalProvider = mock(ObjectProvider.class);
        ChatAgent chatAgent = new ChatAgent(
                mock(ChatClient.class),
                mock(ChatMemory.class),
                mock(MarcelChatPromptComposer.class),
                mock(TaskQueue.class),
                contextHolder,
                dispatcherProvider,
                projectRepository,
                projectWorkspaceRepository,
                new PathValidator(workspaceRoot, externalWorkspaceRegistry(externalWorkspace)),
                mock(ConversationTaskRepository.class),
                mock(TaskQualifier.class),
                journalProvider);
        ReflectionTestUtils.setField(chatAgent, "maxFileReadChars", maxChars);
        return chatAgent;
    }

    private WorkspaceRegistry externalWorkspaceRegistry(Path externalWorkspace) {
        return (absolutePath, projectId) -> externalWorkspace != null
                && Path.of(absolutePath).toAbsolutePath().normalize()
                .startsWith(externalWorkspace.toAbsolutePath().normalize());
    }
}
