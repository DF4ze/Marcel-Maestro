package fr.ses10doigts.mm.app.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBootstrapServiceTest {

    @Test
    @DisplayName("initializeBootstrapConversation enregistre la conversation initiale dans la config projet")
    void initializeBootstrapConversation_storesConversationId(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("PROJECT.md"), "# PROJECT", StandardCharsets.UTF_8);

        ProjectEntity project = ProjectEntity.builder()
                .id("project-a")
                .workspacePath(workspace.toString())
                .build();

        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.save(any(ProjectEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectBootstrapService service = new ProjectBootstrapService(projectRepository, new ObjectMapper());

        service.initializeBootstrapConversation(project, "conv-1");

        assertThat(project.getConfig()).contains("\"bootstrapConversationId\":\"conv-1\"");
    }

    @Test
    @DisplayName("appendUserInputToProject ajoute la réponse utilisateur dans PROJECT.md")
    void appendUserInputToProject_appendsUserAnswer(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path projectFile = workspace.resolve("PROJECT.md");
        Files.writeString(projectFile,
                """
                        # PROJECT

                        ## Notes collectées pendant le cadrage

                        <!-- MARCEL:PROJECT_BOOTSTRAP_NOTES -->
                        """,
                StandardCharsets.UTF_8);

        ProjectEntity project = ProjectEntity.builder()
                .id("project-b")
                .workspacePath(workspace.toString())
                .config("{\"bootstrapConversationId\":\"conv-1\"}")
                .build();

        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findById("project-b")).thenReturn(Optional.of(project));

        ProjectBootstrapService service = new ProjectBootstrapService(projectRepository, new ObjectMapper());

        service.appendUserInputToProject("project-b", "conv-1", "Le projet cible Spring Boot 3 et Java 21.");

        String updated = Files.readString(projectFile, StandardCharsets.UTF_8);
        assertThat(updated).contains("Réponse utilisateur");
        assertThat(updated).contains("Le projet cible Spring Boot 3 et Java 21.");
        assertThat(updated).contains("<!-- MARCEL:PROJECT_BOOTSTRAP_NOTES -->");
    }

    @Test
    @DisplayName("isBootstrapConversation est désactivé si PROJECT.md n'a pas le marqueur bootstrap")
    void isBootstrapConversation_withoutPendingMarker_returnsFalse(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("PROJECT.md"), "# PROJECT", StandardCharsets.UTF_8);

        ProjectEntity project = ProjectEntity.builder()
                .id("project-c")
                .workspacePath(workspace.toString())
                .config("{\"bootstrapConversationId\":\"conv-1\"}")
                .build();

        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findById("project-c")).thenReturn(Optional.of(project));

        ProjectBootstrapService service = new ProjectBootstrapService(projectRepository, new ObjectMapper());

        assertThat(service.isBootstrapConversation("project-c", "conv-1")).isFalse();
    }
}
