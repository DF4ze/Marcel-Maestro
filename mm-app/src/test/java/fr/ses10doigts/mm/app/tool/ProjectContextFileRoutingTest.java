package fr.ses10doigts.mm.app.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.ToolResult;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceEntity;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectContextFileRoutingTest {

    @Test
    @DisplayName("write_file redirige workspace/PROJECT.md vers le workspace du projet courant")
    void writeFile_redirectsProjectContextFile(@TempDir Path tempDir) throws Exception {
        Path globalWorkspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path projectWorkspace = Files.createDirectories(globalWorkspace.resolve("projet-a"));
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findById("project-a")).thenReturn(Optional.of(ProjectEntity.builder()
                .id("project-a")
                .workspacePath(projectWorkspace.toString())
                .build()));

        WriteFileTool tool = new WriteFileTool(globalWorkspace.toString(), projectRepository);
        ToolResult result = tool.execute(
                Map.of("path", "workspace/PROJECT.md", "content", "contenu projet"),
                AgentContext.of("default", "project-a", "conv-1", "task-1"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(projectWorkspace.resolve("PROJECT.md"), StandardCharsets.UTF_8))
                .isEqualTo("contenu projet");
        assertThat(Files.exists(globalWorkspace.resolve("PROJECT.md"))).isFalse();
    }

    @Test
    @DisplayName("write_file écrit un chemin relatif simple dans le workspace du projet courant")
    void writeFile_writesRelativePathInCurrentProjectWorkspace(@TempDir Path tempDir) throws Exception {
        Path globalWorkspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path projectWorkspace = Files.createDirectories(globalWorkspace.resolve("autre"));
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findById("project-autre")).thenReturn(Optional.of(ProjectEntity.builder()
                .id("project-autre")
                .workspacePath(projectWorkspace.toString())
                .build()));

        WriteFileTool tool = new WriteFileTool(globalWorkspace.toString(), projectRepository);
        ToolResult result = tool.execute(
                Map.of("path", "TOTO.txt", "content", "contenu autre"),
                AgentContext.of("default", "project-autre", "conv-1", "task-1"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(projectWorkspace.resolve("TOTO.txt"), StandardCharsets.UTF_8))
                .isEqualTo("contenu autre");
        assertThat(Files.exists(globalWorkspace.resolve("TOTO.txt"))).isFalse();
    }

    @Test
    @DisplayName("read_file lit workspace/PROJECT.md depuis le workspace du projet courant")
    void readFile_redirectsProjectContextFile(@TempDir Path tempDir) throws Exception {
        Path globalWorkspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path projectWorkspace = Files.createDirectories(globalWorkspace.resolve("projet-b"));
        Files.writeString(projectWorkspace.resolve("PROJECT.md"), "source projet", StandardCharsets.UTF_8);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectWorkspaceRepository workspaceRepository = mock(ProjectWorkspaceRepository.class);
        when(projectRepository.findById("project-b")).thenReturn(Optional.of(ProjectEntity.builder()
                .id("project-b")
                .workspacePath(projectWorkspace.toString())
                .build()));

        ReadFileTool tool = new ReadFileTool(globalWorkspace.toString(), projectRepository, workspaceRepository);
        ToolResult result = tool.execute(
                Map.of("path", "workspace/PROJECT.md"),
                AgentContext.of("default", "project-b", "conv-1", "task-1"));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo("source projet");
    }

    @Test
    @DisplayName("read_file lit un chemin relatif simple depuis le workspace du projet courant")
    void readFile_readsRelativePathFromCurrentProjectWorkspace(@TempDir Path tempDir) throws Exception {
        Path globalWorkspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path projectWorkspace = Files.createDirectories(globalWorkspace.resolve("autre"));
        Files.writeString(projectWorkspace.resolve("TOTO.txt"), "contenu projet", StandardCharsets.UTF_8);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectWorkspaceRepository workspaceRepository = mock(ProjectWorkspaceRepository.class);
        when(projectRepository.findById("project-autre")).thenReturn(Optional.of(ProjectEntity.builder()
                .id("project-autre")
                .workspacePath(projectWorkspace.toString())
                .build()));

        ReadFileTool tool = new ReadFileTool(globalWorkspace.toString(), projectRepository, workspaceRepository);
        ToolResult result = tool.execute(
                Map.of("path", "TOTO.txt"),
                AgentContext.of("default", "project-autre", "conv-1", "task-1"));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo("contenu projet");
    }

    @Test
    @DisplayName("read_file cherche dans les workspaces rattaches si le fichier est absent du workspace interne")
    void readFile_fallsBackToAttachedWorkspace(@TempDir Path tempDir) throws Exception {
        Path globalWorkspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path externalWorkspace = Files.createDirectories(tempDir.resolve("repo-externe"));
        Files.createDirectories(externalWorkspace.resolve("src"));
        Files.writeString(externalWorkspace.resolve("src/Main.java"), "external source", StandardCharsets.UTF_8);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectWorkspaceRepository workspaceRepository = mock(ProjectWorkspaceRepository.class);
        when(workspaceRepository.findAllByProjectId("project-c")).thenReturn(List.of(
                ProjectWorkspaceEntity.builder().id("ws-1").path(externalWorkspace.toString()).build()));

        ReadFileTool tool = new ReadFileTool(globalWorkspace.toString(), projectRepository, workspaceRepository);
        ToolResult result = tool.execute(
                Map.of("path", "src/Main.java"),
                AgentContext.of("default", "project-c", "conv-1", "task-1"));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo("external source");
    }
}
