package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.app.project.ProjectBootstrapService;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProjectBootstrapPromptExtensionTest {

    private final AgentContextHolder contextHolder = new AgentContextHolder();

    @AfterEach
    void tearDown() {
        contextHolder.clear();
    }

    @Test
    @DisplayName("contribution active la consigne de cadrage pour la conversation initiale")
    void contribution_whenBootstrapConversation_returnsInstructions() {
        ProjectBootstrapService bootstrapService = mock(ProjectBootstrapService.class);
        when(bootstrapService.isBootstrapConversation("project-a", "conv-1")).thenReturn(true);
        contextHolder.bind(AgentContext.of("default", "project-a", "conv-1", "task-1"));

        ProjectBootstrapPromptExtension extension = new ProjectBootstrapPromptExtension(contextHolder, bootstrapService);

        String contribution = extension.contribution();

        assertThat(contribution).contains("Cette conversation est la discussion de cadrage initial du projet");
        assertThat(contribution).contains("ouvrir une autre discussion");
        assertThat(contribution).contains("discussion dédiée à la construction de la roadmap");
    }
}
