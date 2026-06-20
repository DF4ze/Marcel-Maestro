package fr.ses10doigts.mm.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tests unitaires de {@link ToolRegistry} : enregistrement, resolution avec whitelist,
 * gestion des noms inconnus, doublons, et acces par nom.
 */
class ToolRegistryTest {

    private static final AgentContext CTX = AgentContext.of("default", "p1", "c1", "t1");

    @Test
    void resolveAvecWhitelist_retourneSeulementOutilsAutorises() {
        ToolRegistry registry = new ToolRegistry(List.of(
                stubTool("A"), stubTool("B"), stubTool("C")));
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);

        List<ToolCallback> resolved = registry.resolve(List.of("A", "C"), CTX, guard);

        assertEquals(2, resolved.size());
        assertEquals("A", resolved.get(0).getToolDefinition().name());
        assertEquals("C", resolved.get(1).getToolDefinition().name());
    }

    @Test
    void resolveAvecNomInconnu_ignoreeSansException() {
        ToolRegistry registry = new ToolRegistry(List.of(stubTool("A")));
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);

        List<ToolCallback> resolved = registry.resolve(List.of("A", "INEXISTANT"), CTX, guard);

        assertEquals(1, resolved.size());
        assertEquals("A", resolved.get(0).getToolDefinition().name());
    }

    @Test
    void doublonDeNom_lanceIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(stubTool("X"), stubTool("X"))));
    }

    @Test
    void getParNom_retourneOutilCorrect() {
        ToolRegistry registry = new ToolRegistry(List.of(stubTool("alpha"), stubTool("beta")));

        Optional<AgentTool> result = registry.get("beta");

        assertTrue(result.isPresent());
        assertEquals("beta", result.get().name());
    }

    @Test
    void getParNomInexistant_retourneVide() {
        ToolRegistry registry = new ToolRegistry(List.of(stubTool("alpha")));

        assertTrue(registry.get("inconnu").isEmpty());
    }

    @Test
    void size_retourneNombreOutils() {
        ToolRegistry registry = new ToolRegistry(List.of(stubTool("a"), stubTool("b"), stubTool("c")));
        assertEquals(3, registry.size());
    }

    private static AgentTool stubTool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc " + name; }
            @Override public JsonNode inputSchema() {
                return new ObjectMapper().createObjectNode().put("type", "object");
            }
            @Override public RiskLevel riskLevel() { return RiskLevel.LOW; }
            @Override public ToolResult execute(Map<String, Object> params, AgentContext ctx) {
                return ToolResult.ok("ok");
            }
        };
    }
}
