package fr.ses10doigts.mm.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.engine.parse.AgentResponseParser;
import fr.ses10doigts.mm.core.engine.support.ScriptedChatModel;
import fr.ses10doigts.mm.core.prompt.SystemPromptComposer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Tests de bout en bout de la boucle <strong>sans LLM réel</strong> (étape 3) : le
 * {@link ScriptedChatModel} fournit des réponses JSON scriptées (done, running, blocked,
 * trouble, KO, JSON invalide, JSON tronqué) à travers un {@code ChatClient}.
 */
class AgentLoopTest {

    private static final String DONE =
            "{\"status\":\"done\",\"reason\":\"ok\",\"output\":\"R\",\"tool_calls\":[],\"sub_tasks\":[]}";
    private static final String RUNNING = "{\"status\":\"running\",\"reason\":\"en cours\"}";
    private static final String TROUBLE = "{\"status\":\"trouble\",\"reason\":\"souci\"}";
    private static final String BLOCKED = "{\"status\":\"blocked\",\"reason\":\"besoin validation\"}";
    private static final String KO = "{\"status\":\"KO\",\"reason\":\"impossible\"}";

    private AgentLoop newLoop(LoopConfig config, ScriptedChatModel model) {
        ChatClient client = ChatClient.builder(model).build();
        return new AgentLoop(client, SystemPromptComposer.base(), new AgentResponseParser(),
                new AgentStateMachine(), config, null);
    }

    private TaskMessage task() {
        return new TaskMessage("t1", TaskType.USER_REQUEST, "cortex", "fais X",
                new AgentContext("default", "p1", "c1", "t1"));
    }

    @Test
    void doneDuPremierCoup() {
        ScriptedChatModel model = new ScriptedChatModel().reply(DONE);

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals("R", outcome.lastResponse().output());
        assertEquals(1, outcome.iterations());
        assertEquals(1, model.callCount());
    }

    @Test
    void jsonInvalidePuisDone_relanceSurPromptRenforce() {
        ScriptedChatModel model = new ScriptedChatModel()
                .reply("désolé je n'ai pas de JSON")
                .reply(DONE);

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals(2, outcome.iterations(), "1 échec de parsing (trouble) + 1 done");
        assertEquals(2, model.callCount());
    }

    @Test
    void troubleRepeteDepasseLaBorne_KO() {
        ScriptedChatModel model = new ScriptedChatModel().reply(TROUBLE); // répété ensuite

        AgentOutcome outcome = newLoop(new LoopConfig(50, 2, 50), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.KO, outcome.finalStatus());
        assertTrue(outcome.terminationReason().contains("Trouble".toLowerCase())
                        || outcome.terminationReason().contains("maxTroubleRetries"),
                outcome.terminationReason());
    }

    @Test
    void runningEnBoucle_detecteBoucleInfinie_KO() {
        ScriptedChatModel model = new ScriptedChatModel().reply(RUNNING);

        AgentOutcome outcome = newLoop(new LoopConfig(50, 50, 3), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.KO, outcome.finalStatus());
        assertEquals(3, outcome.iterations());
        assertTrue(outcome.terminationReason().contains("boucle infinie"), outcome.terminationReason());
    }

    @Test
    void atteintMaxIterations_KO() {
        ScriptedChatModel model = new ScriptedChatModel().reply(RUNNING);

        AgentOutcome outcome = newLoop(new LoopConfig(3, 50, 50), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.KO, outcome.finalStatus());
        assertEquals(3, outcome.iterations());
        assertTrue(outcome.terminationReason().contains("maxIterations"), outcome.terminationReason());
    }

    @Test
    void blocked_attenteHumaine() {
        ScriptedChatModel model = new ScriptedChatModel().reply(BLOCKED);

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.BLOCKED, outcome.finalStatus());
        assertEquals("besoin validation", outcome.terminationReason());
    }

    @Test
    void koSignaleParLeLlm() {
        ScriptedChatModel model = new ScriptedChatModel().reply(KO);

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.KO, outcome.finalStatus());
        assertEquals("impossible", outcome.terminationReason());
    }

    @Test
    void jsonTronquePuisDone() {
        ScriptedChatModel model = new ScriptedChatModel()
                .reply("{\"status\":\"run", "length") // tronqué → trouble
                .reply(DONE);

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals(2, model.callCount());
    }

    @Test
    void echecDAppelLlm_traiteCommeTroublePuisDone() {
        ScriptedChatModel model = new ScriptedChatModel()
                .fail(new RuntimeException("réseau down"))
                .reply(DONE);

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), StopSignal.never());

        assertEquals(AgentStatus.DONE, outcome.finalStatus());
        assertEquals(2, model.callCount());
    }

    @Test
    void stopAvantToutAppel_aucunAppelLlm() {
        ScriptedChatModel model = new ScriptedChatModel().reply(DONE);
        StopSignal stop = new StopSignal();
        stop.stop();

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), stop);

        assertEquals(AgentStatus.KO, outcome.finalStatus());
        assertEquals("stopped by user", outcome.terminationReason());
        assertEquals(0, model.callCount(), "STOP vérifié avant l'appel LLM");
        assertNull(outcome.lastResponse());
    }

    @Test
    void stopApresReceptionDuResultat() {
        StopSignal stop = new StopSignal();
        // Le STOP est déclenché pendant l'appel ; il doit être pris en compte juste après
        // la réception du résultat, avant la prochaine itération.
        ScriptedChatModel model = new ScriptedChatModel().reply(RUNNING).onCall(stop::stop);

        AgentOutcome outcome = newLoop(LoopConfig.defaults(), model).run(task(), stop);

        assertEquals(AgentStatus.KO, outcome.finalStatus());
        assertEquals("stopped by user", outcome.terminationReason());
        assertEquals(1, model.callCount());
    }
}
