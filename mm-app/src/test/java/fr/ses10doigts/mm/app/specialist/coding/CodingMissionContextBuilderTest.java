package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires rapides de {@link CodingMissionContextBuilder}.
 */
class CodingMissionContextBuilderTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final MemoryStore memoryStore = mock(MemoryStore.class);

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Construit le contexte CLI depuis le workspace projet et la mémoire")
    void build_readsProjectFilesAndFacts() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("projet-a"));
        Files.writeString(workspace.resolve("PROJECT.md"), "# PROJECT\nContexte projet");
        Files.writeString(workspace.resolve("ROADMAP.md"), "# ROADMAP\nEtat avancement");

        ProjectEntity project = ProjectEntity.builder()
                .id("project-1")
                .name("Projet A")
                .sanitizedName("projet-a")
                .workspacePath(workspace.toString())
                .createdAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .build();
        AgentContext ctx = AgentContext.of("default", "project-1", "conv-1", "task-1");

        when(projectRepository.findById("project-1")).thenReturn(Optional.of(project));
        when(memoryStore.findByScope("global", ctx)).thenReturn(List.of(
                new MemoryEntry("g1", "fact global", "global", "default", Instant.now(), Instant.now()),
                new MemoryEntry("g2", "fact commune", "global", "default", Instant.now(), Instant.now())));
        when(memoryStore.findByScope("project:project-1", ctx)).thenReturn(List.of(
                new MemoryEntry("p1", "fact projet", "project:project-1", "default", Instant.now(), Instant.now()),
                new MemoryEntry("p2", "fact commune", "project:project-1", "default", Instant.now(), Instant.now())));

        CodingMissionContextBuilder builder = new CodingMissionContextBuilder(projectRepository, memoryStore);

        MarcelContext context = builder.build(ctx);

        assertThat(context.getWorkingDirectory()).isEqualTo(workspace.toAbsolutePath().normalize().toString());
        assertThat(context.getProjectMd()).contains("Contexte projet");
        assertThat(context.getRoadmapResultMd()).contains("Etat avancement");
        assertThat(context.getC3Facts()).containsExactly("fact global", "fact commune", "fact projet");
    }
}
