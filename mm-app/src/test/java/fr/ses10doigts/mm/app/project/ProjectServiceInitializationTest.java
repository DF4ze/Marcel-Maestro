package fr.ses10doigts.mm.app.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * Tests unitaires légers de l'initialisation du workspace projet.
 */
class ProjectServiceInitializationTest {

    @Test
    @DisplayName("create initialise PROJECT.md et ROADMAP.md dans le nouveau workspace")
    void create_initializesContextFiles(@TempDir Path tempDir) throws IOException {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findBySanitizedName("mon-projet")).thenReturn(Optional.empty());
        when(projectRepository.save(any(ProjectEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceProperties workspaceProperties = new WorkspaceProperties();
        workspaceProperties.setRoot(tempDir.toString());

        ProjectService projectService = new ProjectService(
                projectRepository,
                mock(ProjectWorkspaceRepository.class),
                mock(ConversationRepository.class),
                mock(ChatMemory.class),
                workspaceProperties);

        ProjectEntity project = projectService.create("Mon Projet");

        Path workspacePath = Path.of(project.getWorkspacePath());
        assertThat(Files.exists(workspacePath.resolve("PROJECT.md"))).isTrue();
        assertThat(Files.exists(workspacePath.resolve("ROADMAP.md"))).isTrue();
        assertThat(Files.readString(workspacePath.resolve("PROJECT.md")))
                .contains("pose des questions ciblées")
                .contains("Objectif");
        assertThat(Files.readString(workspacePath.resolve("ROADMAP.md")))
                .contains("plan d'exécution réaliste")
                .contains("Milestones");
    }
}
