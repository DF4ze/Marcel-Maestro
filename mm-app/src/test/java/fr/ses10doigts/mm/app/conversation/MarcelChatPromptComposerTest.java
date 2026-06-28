package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.specialist.coding.CodingAgentsProperties;
import fr.ses10doigts.mm.app.specialist.coding.CodingRoutingPromptExtension;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import fr.ses10doigts.mm.starter.prompt.ProjectContextExtension;
import fr.ses10doigts.mm.starter.prompt.ProjectSystemPromptExtension;
import fr.ses10doigts.mm.starter.prompt.ToolsSystemPromptExtension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests unitaires de {@link MarcelChatPromptComposer}.
 */
class MarcelChatPromptComposerTest {

    private final AgentContextHolder contextHolder = new AgentContextHolder();
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectWorkspaceRepository projectWorkspaceRepository = mock(ProjectWorkspaceRepository.class);

    @AfterEach
    void tearDown() {
        contextHolder.clear();
    }

    @Test
    @DisplayName("Les extensions M0 et M3 coexistent dans le prompt composé")
    void compose_withProjectExtensions_containsMetadataAndFileContents(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-root"));
        Path projectWorkspace = Files.createDirectories(workspaceRoot.resolve("project-a"));
        Files.writeString(projectWorkspace.resolve("PROJECT.md"), "Stack : Spring Boot 3", StandardCharsets.UTF_8);
        Files.writeString(projectWorkspace.resolve("ROADMAP.md"), "Milestone : E3-M3", StandardCharsets.UTF_8);
        when(projectRepository.findById("project-a")).thenReturn(Optional.of(ProjectEntity.builder()
                .id("project-a")
                .name("Marcel Maestro")
                .workspacePath(projectWorkspace.toString())
                .build()));
        contextHolder.bind(AgentContext.of("default", "project-a", "conv-1", "task-1"));

        MarcelChatPromptComposer composer = new MarcelChatPromptComposer(List.of(
                new ProjectSystemPromptExtension(contextHolder, projectRepository),
                new ProjectContextExtension(
                        contextHolder,
                        projectRepository,
                        projectWorkspaceRepository,
                        new PathValidator(workspaceRoot),
                        3000)));
        ReflectionTestUtils.setField(composer, "basePrompt", "Prompt de base Marcel");

        String prompt = composer.compose();

        assertThat(prompt).contains("Prompt de base Marcel");
        assertThat(prompt).contains("CONTEXTE PROJET COURANT");
        assertThat(prompt).contains("Marcel Maestro");
        assertThat(prompt).contains("### PROJECT.md");
        assertThat(prompt).contains("Stack : Spring Boot 3");
        assertThat(prompt).contains("### ROADMAP.md");
        assertThat(prompt).contains("Milestone : E3-M3");
    }

    @Test
    @DisplayName("Le prompt conversationnel exclut les extensions d'outils moteur")
    void compose_excludesEngineOnlyExtensions() {
        ToolsSystemPromptExtension toolsExtension =
                new ToolsSystemPromptExtension(List.of(new StubTool("write_file", "ecrit un fichier")));
        CodingRoutingPromptExtension routingExtension = new CodingRoutingPromptExtension(new CodingAgentsProperties());

        MarcelChatPromptComposer composer = new MarcelChatPromptComposer(List.of(toolsExtension, routingExtension));
        ReflectionTestUtils.setField(composer, "basePrompt",
                "Utilise submit_task pour les actions reelles et read_project_file pour la lecture.");

        String prompt = composer.compose();

        assertThat(prompt).contains("Utilise submit_task");
        assertThat(prompt).doesNotContain("OUTILS DISPONIBLES");
        assertThat(prompt).doesNotContain("write_file");
        assertThat(prompt).doesNotContain("REGLES DE DELEGATION");
    }

    private static class StubTool implements AgentTool {

        private final String name;
        private final String description;

        private StubTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of(
                    "type", "object",
                    "properties", Map.of("path", Map.of("description", "chemin"))));
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.HIGH;
        }

        @Override
        public fr.ses10doigts.mm.core.tool.ToolResult execute(Map<String, Object> params, AgentContext ctx) {
            throw new UnsupportedOperationException();
        }
    }
}
