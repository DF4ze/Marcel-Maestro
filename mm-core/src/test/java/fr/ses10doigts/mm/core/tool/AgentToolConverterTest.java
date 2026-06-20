package fr.ses10doigts.mm.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tests unitaires de {@link AgentToolConverter} : conversion d'un {@link AgentTool}
 * en {@link ToolCallback} Spring AI, passage des parametres JSON, execution via
 * le guard, et serialisation du resultat.
 */
class AgentToolConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentContext ctx = AgentContext.of("default", "p1", "c1", "t1");

    @Test
    void conversionProduitCallbackAvecNomEtDescription() {
        AgentTool tool = new StubTool("mon_outil", "Ma description");
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);

        ToolCallback callback = AgentToolConverter.toCallback(tool, ctx, guard);

        assertEquals("mon_outil", callback.getToolDefinition().name());
        assertEquals("Ma description", callback.getToolDefinition().description());
    }

    @Test
    void callAvecParamsJson_executeEtRetourneSerialisé() throws Exception {
        StubTool tool = new StubTool("echo", "Echo tool");
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);
        ToolCallback callback = AgentToolConverter.toCallback(tool, ctx, guard);

        String resultJson = callback.call("{\"key\":\"value\"}");

        assertNotNull(resultJson);
        JsonNode node = MAPPER.readTree(resultJson);
        assertTrue(node.get("success").asBoolean());
        assertNotNull(node.get("data"));
        assertEquals(1, tool.callCount);
    }

    @Test
    void callAvecJsonInvalide_retourneEchec() throws Exception {
        StubTool tool = new StubTool("echo", "Echo tool");
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);
        ToolCallback callback = AgentToolConverter.toCallback(tool, ctx, guard);

        String resultJson = callback.call("pas du json{{");

        JsonNode node = MAPPER.readTree(resultJson);
        assertEquals(false, node.get("success").asBoolean());
        assertTrue(node.get("error").asText().contains("invalid input"));
        assertEquals(0, tool.callCount, "l'outil ne doit pas etre appele si le JSON est invalide");
    }

    /**
     * Outil stub minimal pour les tests.
     */
    private static class StubTool implements AgentTool {
        private final String name;
        private final String description;
        int callCount = 0;

        StubTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public JsonNode inputSchema() {
            return new ObjectMapper().createObjectNode().put("type", "object");
        }
        @Override public RiskLevel riskLevel() { return RiskLevel.LOW; }
        @Override public ToolResult execute(Map<String, Object> params, AgentContext ctx) {
            callCount++;
            return ToolResult.ok("echo:" + params);
        }
    }
}
