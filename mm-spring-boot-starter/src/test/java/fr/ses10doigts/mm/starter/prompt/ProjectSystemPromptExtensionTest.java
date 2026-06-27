package fr.ses10doigts.mm.starter.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link ProjectSystemPromptExtension}.
 */
class ProjectSystemPromptExtensionTest {

    private final AgentContextHolder contextHolder = new AgentContextHolder();
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);

    @AfterEach
    void tearDown() {
        contextHolder.clear();
    }

    @Test
    @DisplayName("Avec projectId valide, le prompt composé contient le nom du projet")
    void contribution_withValidProjectId_containsProjectName() {
        when(projectRepository.findById("project-1")).thenReturn(Optional.of(ProjectEntity.builder()
                .id("project-1")
                .name("Marcel Maestro")
                .workspacePath("/workspace/marcel-maestro")
                .build()));
        contextHolder.bind(AgentContext.of("default", "project-1", "conv-1", "task-1"));
        ProjectSystemPromptExtension extension =
                new ProjectSystemPromptExtension(contextHolder, projectRepository);
        SystemPromptComposer composer = new SystemPromptComposer(List.of(extension));

        String prompt = composer.compose();

        assertThat(prompt).contains("Marcel Maestro");
        assertThat(prompt).contains("/workspace/marcel-maestro");
        assertThat(prompt).contains("CONTEXTE PROJET COURANT");
    }

    @Test
    @DisplayName("Avec projectId null, aucune section projet n'est ajoutée")
    void contribution_withNullProjectId_returnsEmptyContribution() {
        contextHolder.bind(AgentContext.of("default", null, "conv-1", "task-1"));
        ProjectSystemPromptExtension extension =
                new ProjectSystemPromptExtension(contextHolder, projectRepository);
        SystemPromptComposer composer = new SystemPromptComposer(List.of(extension));

        String prompt = composer.compose();

        assertThat(prompt).doesNotContain("CONTEXTE PROJET COURANT");
    }

    @Test
    @DisplayName("Sans contexte courant, aucune exception et contribution vide")
    void contribution_withoutContext_returnsEmptyContribution() {
        ProjectSystemPromptExtension extension =
                new ProjectSystemPromptExtension(contextHolder, projectRepository);

        assertThat(extension.contribution()).isEmpty();
    }
}
