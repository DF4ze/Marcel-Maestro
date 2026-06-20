package fr.ses10doigts.mm.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.engine.support.ScriptedHumanInteraction;
import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlGuard;
import fr.ses10doigts.mm.core.hitl.HitlPolicy;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires de {@link ToolExecutionGuard} : verification HITL (deny/allow),
 * timeout d'execution, execution directe sans guard, et rejet par PathValidator.
 */
class ToolExecutionGuardTest {

    private static final AgentContext CTX = AgentContext.of("default", "p1", "c1", "t1");

    @TempDir
    Path workspace;

    @Test
    void hitlDeny_retourneEchec() {
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction().respond(ConsentDecision.DENY);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, null);

        AgentTool tool = stubTool("danger", RiskLevel.HIGH);
        ToolResult result = guard.execute(tool, Map.of(), CTX);

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("denied"));
    }

    @Test
    void hitlAllow_executeNormalement() {
        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction()
                .respond(ConsentDecision.ALLOW_ONCE);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, null);

        AgentTool tool = stubTool("safe", RiskLevel.HIGH);
        ToolResult result = guard.execute(tool, Map.of("key", "val"), CTX);

        assertTrue(result.success());
        assertNotNull(result.data());
    }

    @Test
    void outilTropLent_retourneTimeout() {
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);

        AgentTool slowTool = new AgentTool() {
            @Override public String name() { return "slow"; }
            @Override public String description() { return "Slow tool"; }
            @Override public JsonNode inputSchema() {
                return new ObjectMapper().createObjectNode().put("type", "object");
            }
            @Override public RiskLevel riskLevel() { return RiskLevel.LOW; }
            @Override public long maxExecutionTimeMs() { return 100; }
            @Override public ToolResult execute(Map<String, Object> params, AgentContext ctx) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { /* ignore */ }
                return ToolResult.ok("trop tard");
            }
        };

        ToolResult result = guard.execute(slowTool, Map.of(), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("timeout"));
    }

    @Test
    void sansHitlGuard_executeDirectement() {
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);
        AgentTool tool = stubTool("direct", RiskLevel.HIGH);

        ToolResult result = guard.execute(tool, Map.of("a", "b"), CTX);

        assertTrue(result.success());
    }

    @Test
    void pathValidatorRejetteCheminDangereux() {
        PathValidator pathValidator = new PathValidator(workspace);
        ToolExecutionGuard guard = new ToolExecutionGuard(null, pathValidator);
        AgentTool tool = stubTool("fs_tool", RiskLevel.LOW);

        ToolResult result = guard.execute(tool, Map.of("file", "../../etc/passwd"), CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("path violation"));
    }

    private static AgentTool stubTool(String name, RiskLevel risk) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Stub " + name; }
            @Override public JsonNode inputSchema() {
                return new ObjectMapper().createObjectNode().put("type", "object");
            }
            @Override public RiskLevel riskLevel() { return risk; }
            @Override public ToolResult execute(Map<String, Object> params, AgentContext ctx) {
                return ToolResult.ok("result:" + params);
            }
        };
    }
}
