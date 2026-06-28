package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires rapides de {@link TaskRouter}.
 */
class TaskRouterTest {

    @Test
    @DisplayName("Résout chaque catégorie vers l'agent configuré")
    void resolve_withConfiguredRouting_returnsExpectedAgent() {
        CodingAgentsProperties properties = new CodingAgentsProperties();
        SpecialistAgentPort claude = (task, context) -> AgentReport.ko("claude");
        SpecialistAgentPort codex = (task, context) -> AgentReport.ko("codex");
        TaskRouter router = new TaskRouter(Map.of("claude", claude, "codex", codex), properties);

        assertThat(router.resolve(TaskCategory.CODING)).isSameAs(claude);
        assertThat(router.resolve(TaskCategory.ANALYSIS)).isSameAs(claude);
        assertThat(router.resolve(TaskCategory.BUILD)).isSameAs(codex);
    }
}
