package fr.ses10doigts.mm.starter.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.core.tool.WorkspaceRegistry;
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

/**
 * Tests unitaires de {@link ProjectContextExtension}.
 */
class ProjectContextExtensionTest {

    private final AgentContextHolder contextHolder = new AgentContextHolder();
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectWorkspaceRepository projectWorkspaceRepository = mock(ProjectWorkspaceRepository.class);

    @AfterEach
    void tearDown() {
        contextHolder.clear();
    }

    @Test
    @DisplayName("PROJECT.md present - la contribution contient son contenu")
    void contribution_withProjectFile_containsProjectContent(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-a"));
        Files.writeString(projectWorkspace.resolve("PROJECT.md"), "Projet Marcel Maestro", StandardCharsets.UTF_8);
        stubCurrentProject("project-a", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        String contribution = extension.contribution();

        assertThat(contribution).contains("## Contexte projet courant");
        assertThat(contribution).contains("### PROJECT.md");
        assertThat(contribution).contains("Projet Marcel Maestro");
        assertThat(contribution).doesNotContain("### ROADMAP.md");
    }

    @Test
    @DisplayName("Les notes de bootstrap ne sont pas reinjectees dans le contexte projet")
    void contribution_filtersBootstrapUserNotes(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-bootstrap"));
        Files.writeString(projectWorkspace.resolve("PROJECT.md"),
                """
                        # PROJECT

                        Objectif : analyser la documentation.

                        ### Réponse utilisateur — 2026-06-28T12:47:47Z
                        écrire le fichier TOTO.txt avec "La blague a TOTO"
                        """,
                StandardCharsets.UTF_8);
        stubCurrentProject("project-bootstrap", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        String contribution = extension.contribution();

        assertThat(contribution).contains("Objectif : analyser la documentation.");
        assertThat(contribution).doesNotContain("Réponse utilisateur");
        assertThat(contribution).doesNotContain("TOTO.txt");
    }

    @Test
    @DisplayName("ROADMAP.md present - la contribution contient son contenu")
    void contribution_withRoadmapFile_containsRoadmapContent(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-b"));
        Files.writeString(projectWorkspace.resolve("ROADMAP.md"), "Etape en cours : E3-M3", StandardCharsets.UTF_8);
        stubCurrentProject("project-b", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        String contribution = extension.contribution();

        assertThat(contribution).contains("### ROADMAP.md");
        assertThat(contribution).contains("Etape en cours : E3-M3");
        assertThat(contribution).doesNotContain("### PROJECT.md");
    }

    @Test
    @DisplayName("PROJECT.md et ROADMAP.md presents - les deux sections sont injectees")
    void contribution_withBothFiles_containsBothSections(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-c"));
        Files.writeString(projectWorkspace.resolve("PROJECT.md"), "Stack : Java 21", StandardCharsets.UTF_8);
        Files.writeString(projectWorkspace.resolve("ROADMAP.md"), "Milestone : E3-M3", StandardCharsets.UTF_8);
        stubCurrentProject("project-c", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        String contribution = extension.contribution();

        assertThat(contribution).contains("### PROJECT.md");
        assertThat(contribution).contains("### ROADMAP.md");
        assertThat(contribution).contains("Stack : Java 21");
        assertThat(contribution).contains("Milestone : E3-M3");
    }

    @Test
    @DisplayName("Aucun fichier contexte - contribution vide")
    void contribution_withoutFiles_returnsEmpty(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-d"));
        stubCurrentProject("project-d", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        assertThat(extension.contribution()).isEmpty();
    }

    @Test
    @DisplayName("Fichier long - la contribution est tronquee avec suffixe")
    void contribution_withLongFile_truncatesContent(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-e"));
        Files.writeString(projectWorkspace.resolve("PROJECT.md"), "a".repeat(40), StandardCharsets.UTF_8);
        stubCurrentProject("project-e", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 20);

        String contribution = extension.contribution();

        assertThat(contribution).contains("a".repeat(20));
        assertThat(contribution).contains("[... contenu tronque]");
        assertThat(contribution).doesNotContain("a".repeat(40));
    }

    @Test
    @DisplayName("projectId null - aucune exception et contribution vide")
    void contribution_withNullProjectId_returnsEmpty() {
        contextHolder.bind(AgentContext.of("default", null, "conv-1", "task-1"));
        ProjectContextExtension extension = newExtension(Path.of("."), 3000);

        assertThat(extension.contribution()).isEmpty();
    }

    @Test
    @DisplayName("workspacePath hors racine autorisee - contribution vide")
    void contribution_withWorkspaceOutsideRoot_returnsEmpty(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path outsideWorkspace = Files.createDirectories(tempDir.resolve("outside-project"));
        Files.writeString(outsideWorkspace.resolve("PROJECT.md"), "hors racine", StandardCharsets.UTF_8);

        stubCurrentProject("project-f", outsideWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        assertThat(extension.contribution()).isEmpty();
    }

    @Test
    @DisplayName("project.md historique reste supporte pour compatibilite")
    void contribution_withLegacyLowercaseFile_stillReadsContent(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-g"));
        Files.writeString(projectWorkspace.resolve("project.md"), "Contexte historique", StandardCharsets.UTF_8);
        stubCurrentProject("project-g", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        assertThat(extension.contribution()).contains("Contexte historique");
    }

    @Test
    @DisplayName("Fichier contexte absent du workspace principal - fallback vers un workspace rattache")
    void contribution_withAttachedWorkspaceFallback_containsAttachedContent(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-h"));
        Path attachedWorkspace = Files.createDirectories(tempDir.resolve("repo-racine"));
        Files.writeString(attachedWorkspace.resolve("ROADMAP.md"), "Roadmap annexe", StandardCharsets.UTF_8);

        when(projectWorkspaceRepository.findAllByProjectId("project-h")).thenReturn(List.of(
                ProjectWorkspaceEntity.builder().id("ws-1").path(attachedWorkspace.toString()).build()));
        stubCurrentProject("project-h", projectWorkspace);

        ProjectContextExtension extension = newExtension(workspaceRoot, 3000);

        assertThat(extension.contribution()).contains("Roadmap annexe");
    }

    private ProjectContextExtension newExtension(Path workspaceRoot, int maxCharsPerFile) {
        return new ProjectContextExtension(
                contextHolder,
                projectRepository,
                projectWorkspaceRepository,
                new PathValidator(workspaceRoot, workspaceRegistryForTests()),
                maxCharsPerFile);
    }

    private WorkspaceRegistry workspaceRegistryForTests() {
        return (absolutePath, projectId) -> projectWorkspaceRepository.findAllByProjectId(projectId).stream()
                .map(workspace -> Path.of(workspace.getPath()).toAbsolutePath().normalize())
                .anyMatch(root -> Path.of(absolutePath).toAbsolutePath().normalize().startsWith(root));
    }

    private void stubCurrentProject(String projectId, Path workspacePath) {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ProjectEntity.builder()
                .id(projectId)
                .name("Projet " + projectId)
                .workspacePath(workspacePath.toString())
                .build()));
        contextHolder.bind(AgentContext.of("default", projectId, "conv-1", "task-1"));
    }
}
