package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.engine.StopSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires rapides de {@link CodexAgentFactoryAdapter}.
 */
class CodexAgentFactoryAdapterTest {

    @Test
    @DisplayName("Exécute Codex via le mapper de mission puis convertit le rapport en outcome")
    void execute_mapsMissionRunsSpecialistAndReturnsOutcome() {
        TaskMessageCodingMissionMapper missionMapper = mock(TaskMessageCodingMissionMapper.class);
        CodexAgent specialistAgent = mock(CodexAgent.class);
        CodingAgentOutcomeMapper outcomeMapper = mock(CodingAgentOutcomeMapper.class);
        CodexAgentFactoryAdapter adapter = new CodexAgentFactoryAdapter(specialistAgent, missionMapper, outcomeMapper);

        TaskMessage task = new TaskMessage(
                "task-1",
                TaskType.SPECIALIST_REQUEST,
                "codex",
                "Lance le build",
                AgentContext.of("default", "project-1", "conv-1", "task-1"));
        AgentTask agentTask = AgentTask.builder()
                .id("task-1")
                .title("Lance le build")
                .description("Lance le build")
                .category(TaskCategory.BUILD)
                .build();
        MarcelContext context = MarcelContext.builder().workingDirectory("D:/work/project").build();
        TaskMessageCodingMissionMapper.CodingMission mission =
                new TaskMessageCodingMissionMapper.CodingMission(agentTask, context);
        AgentReport report = AgentReport.builder()
                .status(AgentReport.Status.KO)
                .summary("ko")
                .factsDiscovered(java.util.List.of())
                .decisions(java.util.List.of())
                .blocker("build casse")
                .build();
        AgentOutcome outcome = new AgentOutcome(AgentStatus.KO, null, 1, "ko");

        when(missionMapper.map(task, TaskCategory.BUILD)).thenReturn(mission);
        when(specialistAgent.execute(agentTask, context)).thenReturn(report);
        when(outcomeMapper.toOutcome(report)).thenReturn(outcome);

        AgentOutcome actual = adapter.execute(task, StopSignal.never());

        assertThat(actual).isSameAs(outcome);
        verify(missionMapper).map(task, TaskCategory.BUILD);
        verify(specialistAgent).execute(agentTask, context);
        verify(outcomeMapper).toOutcome(report);
    }
}
