package fr.ses10doigts.mm.starter.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.core.tool.ToolResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'intégration de l'outil {@link RememberFactTool} (étape 5, livrable 4).
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("slow")
class RememberFactToolTest {

    @Autowired
    private RememberFactTool rememberFactTool;

    @Autowired
    private MemoryStore memoryStore;

    private final AgentContext ctx = AgentContext.of("default", "test-project", "conv-1", "task-1");

    @Test
    @DisplayName("métadonnées de l'outil conformes")
    void metadonnees() {
        assertThat(rememberFactTool.name()).isEqualTo("remember_fact");
        assertThat(rememberFactTool.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(rememberFactTool.description()).isNotBlank();
        assertThat(rememberFactTool.inputSchema()).isNotNull();
        assertThat(rememberFactTool.inputSchema().get("properties").has("key")).isTrue();
        assertThat(rememberFactTool.inputSchema().get("properties").has("value")).isTrue();
    }

    @Test
    @DisplayName("execute persiste un fait dans le MemoryStore")
    void executePersisteFait() throws ToolException {
        ToolResult result = rememberFactTool.execute(
                Map.of("key", "user:lang", "value", "Java", "scope", "project:mm"),
                ctx);

        assertThat(result.success()).isTrue();

        Optional<MemoryEntry> stored = memoryStore.get("user:lang", ctx);
        assertThat(stored).isPresent();
        assertThat(stored.get().value()).isEqualTo("Java");
        assertThat(stored.get().scope()).isEqualTo("project:mm");
    }

    @Test
    @DisplayName("execute utilise le scope 'global' par défaut")
    void scopeGlobalParDefaut() throws ToolException {
        rememberFactTool.execute(Map.of("key", "pref:color", "value", "blue"), ctx);

        Optional<MemoryEntry> stored = memoryStore.get("pref:color", ctx);
        assertThat(stored).isPresent();
        assertThat(stored.get().scope()).isEqualTo("global");
    }

    @Test
    @DisplayName("execute lève ToolException si key est absent")
    void keyAbsent() {
        assertThatThrownBy(() -> rememberFactTool.execute(Map.of("value", "test"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("key");
    }

    @Test
    @DisplayName("execute lève ToolException si value est absent")
    void valueAbsent() {
        assertThatThrownBy(() -> rememberFactTool.execute(Map.of("key", "test"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("value");
    }
}
