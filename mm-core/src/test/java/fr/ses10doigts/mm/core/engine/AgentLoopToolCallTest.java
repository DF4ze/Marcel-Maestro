package fr.ses10doigts.mm.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.engine.support.ScriptedChatModel;
import fr.ses10doigts.mm.core.engine.support.ScriptedHumanInteraction;
import fr.ses10doigts.mm.core.hitl.ConsentCache;
import fr.ses10doigts.mm.core.hitl.ConsentDecision;
import fr.ses10doigts.mm.core.hitl.HitlGuard;
import fr.ses10doigts.mm.core.hitl.HitlPolicy;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolExecutionGuard;
import fr.ses10doigts.mm.core.tool.ToolRegistry;
import fr.ses10doigts.mm.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Tests d'integration de l'execution des tool_calls dans la boucle agentique (etape 6).
 * Verifie que le moteur execute les outils, injecte les resultats, gere les outils
 * inconnus, les refus HITL, et le cas sans ToolRegistry.
 */
class AgentLoopToolCallTest {

    private static final String RUNNING_WITH_TOOL = """
            {"status":"running","reason":"besoin d'un outil","tool_calls":[{"tool":"test_tool","params":{"key":"value"}}],"sub_tasks":[]}""";
    private static final String RUNNING_WITH_UNKNOWN_TOOL = """
            {"status":"running","reason":"besoin d'un outil","tool_calls":[{"tool":"outil_inconnu","params":{}}],"sub_tasks":[]}""";
    private static final String RUNNING_WITH_HIGH_RISK_TOOL = """
            {"status":"running","reason":"besoin d'un outil","tool_calls":[{"tool":"danger_tool","params":{}}],"sub_tasks":[]}""";
    private static final String DONE = """
            {"status":"done","reason":"ok","output":"R","tool_calls":[],"sub_tasks":[]}""";
    private static final String RUNNING = """
            {"status":"running","reason":"en cours","tool_calls":[],"sub_tasks":[]}""";

    private AgentLoop newLoop(ScriptedChatModel model, ToolRegistry registry,
                              ToolExecutionGuard guard) {
        ChatClient client = ChatClient.builder(model).build();
        return new AgentLoop(client, SystemPromptComposer.base(), new AgentResponseParser(),
                new AgentStateMachine(), LoopConfig.defaults(), null, null, registry, guard);
    }

    private AgentLoop newLoopSansRegistry(ScriptedChatModel model) {
        ChatClient client = ChatClient.builder(model).build();
        return new AgentLoop(client, SystemPromptComposer.base(), new AgentResponseParser(),
                new AgentStateMachine(), LoopConfig.defaults(), null);
    }

    private TaskMessage task() {
        return new TaskMessage("t1", TaskType.USER_REQUEST, "cortex", "fais X",
                AgentContext.of("default", "p1", "c1", "t1"));
    }

    @Test
    void toolCallExecuteEtInjecteResultat() {
        ScriptedChatModel model = new ScriptedChatModel()
                .reply(RUNNING_WITH_TOOL)
                .reply(DONE);

        MockTool tool = new MockTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);

        AgentOutcome outcome = newLoop(model, registry, guard).run(task(), StopSignal.never());

        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals("R", outcome.lastResponse().output());
        assertEquals(2, outcome.iterations());
        assertEquals(1, tool.callCount, "l'outil doit etre appele une fois");
    }

    @Test
    void toolCallOutilInconnu_continueAvecErreur() {
        ScriptedChatModel model = new ScriptedChatModel()
                .reply(RUNNING_WITH_UNKNOWN_TOOL)
                .reply(DONE);

        MockTool tool = new MockTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ToolExecutionGuard guard = new ToolExecutionGuard(null, null);

        AgentOutcome outcome = newLoop(model, registry, guard).run(task(), StopSignal.never());

        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals(2, outcome.iterations());
        assertEquals(0, tool.callCount, "l'outil connu ne doit pas etre appele");
    }

    @Test
    void toolCallDeny_continueAvecRefus() {
        ScriptedChatModel model = new ScriptedChatModel()
                .reply(RUNNING_WITH_HIGH_RISK_TOOL)
                .reply(DONE);

        MockTool dangerTool = new MockTool("danger_tool", RiskLevel.HIGH);
        ToolRegistry registry = new ToolRegistry(List.of(dangerTool));

        ScriptedHumanInteraction hitl = new ScriptedHumanInteraction().respond(ConsentDecision.DENY);
        HitlGuard hitlGuard = new HitlGuard(HitlPolicy.defaults(), new ConsentCache(), hitl);
        ToolExecutionGuard guard = new ToolExecutionGuard(hitlGuard, null);

        AgentOutcome outcome = newLoop(model, registry, guard).run(task(), StopSignal.never());

        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals(2, outcome.iterations());
        assertEquals(0, dangerTool.callCount, "l'outil refuse ne doit pas etre execute");
        assertEquals(1, hitl.askCount(), "HITL demande une fois");
    }

    @Test
    void sansRegistry_toolCallsIgnores() {
        ScriptedChatModel model = new ScriptedChatModel()
                .reply(RUNNING_WITH_TOOL)
                .reply(DONE);

        AgentOutcome outcome = newLoopSansRegistry(model).run(task(), StopSignal.never());

        // Sans registry, RUNNING with tool_calls est traite comme un RUNNING normal
        // => la boucle continue avec un prompt de continuation
        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals(2, outcome.iterations());
    }

    /**
     * Outil mock minimal pour les tests de la boucle.
     */
    private static class MockTool implements AgentTool {
        private final String name;
        private final RiskLevel riskLevel;
        int callCount = 0;

        MockTool() {
            this("test_tool", RiskLevel.LOW);
        }

        MockTool(String name, RiskLevel riskLevel) {
            this.name = name;
            this.riskLevel = riskLevel;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "Test tool " + name; }
        @Override public JsonNode inputSchema() {
            return new ObjectMapper().createObjectNode().put("type", "object");
        }
        @Override public RiskLevel riskLevel() { return riskLevel; }
        @Override public ToolResult execute(Map<String, Object> params, AgentContext ctx) {
            callCount++;
            return ToolResult.ok("result:" + params);
        }
    }
}
