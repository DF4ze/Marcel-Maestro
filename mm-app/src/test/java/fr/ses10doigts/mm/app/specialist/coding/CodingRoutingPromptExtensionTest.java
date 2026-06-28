package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires rapides de {@link CodingRoutingPromptExtension}.
 */
class CodingRoutingPromptExtensionTest {

    @Test
    @DisplayName("Expose dans le prompt les assignees Claude et Codex configurés")
    void contribution_mentionsConfiguredAssignees() {
        CodingAgentsProperties properties = new CodingAgentsProperties();
        properties.getRouting().put(TaskCategory.CODING, "claude");
        properties.getRouting().put(TaskCategory.ANALYSIS, "claude");
        properties.getRouting().put(TaskCategory.BUILD, "codex");

        CodingRoutingPromptExtension extension = new CodingRoutingPromptExtension(properties);

        String contribution = extension.contribution();

        assertThat(contribution).contains("USER_REQUEST -> cortex -> sub_tasks");
        assertThat(contribution).contains("claude : code, refacto, bugfix");
        assertThat(contribution).contains("codex : build Maven, shell, scripts, CI, ops");
    }
}
