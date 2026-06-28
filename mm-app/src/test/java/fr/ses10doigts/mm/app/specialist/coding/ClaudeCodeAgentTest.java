package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires rapides de {@link ClaudeCodeAgent}.
 */
class ClaudeCodeAgentTest {

    private final CrossPlatformRunner runner = mock(CrossPlatformRunner.class);
    private final MissionBriefBuilder missionBriefBuilder = mock(MissionBriefBuilder.class);
    private final ReportExtractor reportExtractor = mock(ReportExtractor.class);
    private final CodingAgentsProperties properties = buildProperties();

    @Test
    @DisplayName("Construit les args Claude et mappe le rapport extrait")
    void execute_withResolvedBinary_runsClaudeAndReturnsExtractedReport() {
        ClaudeCodeAgent agent = new ClaudeCodeAgent(runner, missionBriefBuilder, reportExtractor, properties);
        AgentTask task = AgentTask.builder().id("task-1").title("Titre").description("Description").build();
        MarcelContext context = MarcelContext.builder().workingDirectory("D:/work/project").build();
        AgentReport expected = AgentReport.builder()
                .status(AgentReport.Status.DONE)
                .summary("ok")
                .factsDiscovered(List.of())
                .decisions(List.of())
                .blocker(null)
                .build();

        when(runner.resolveBinary("claude")).thenReturn(Optional.of(Path.of("C:/bin/claude.cmd")));
        when(missionBriefBuilder.build(task, context)).thenReturn("brief");
        when(runner.run(Path.of("C:/bin/claude.cmd"),
                List.of("--output-format", "text", "--permission-mode", "acceptEdits", "--print", "brief"),
                Path.of("D:/work/project"), 1800))
                .thenReturn(ProcessResult.builder().output("raw-output").exitCode(0).build());
        when(reportExtractor.extract("raw-output", 0)).thenReturn(expected);

        AgentReport report = agent.execute(task, context);

        assertThat(report).isSameAs(expected);
        verify(runner).run(eq(Path.of("C:/bin/claude.cmd")),
                eq(List.of("--output-format", "text", "--permission-mode", "acceptEdits", "--print", "brief")),
                eq(Path.of("D:/work/project")),
                eq(1800));
    }

    @Test
    @DisplayName("Retourne ko si le binaire Claude est introuvable")
    void execute_withoutResolvedBinary_returnsKo() {
        ClaudeCodeAgent agent = new ClaudeCodeAgent(runner, missionBriefBuilder, reportExtractor, properties);
        AgentTask task = AgentTask.builder().id("task-1").build();
        MarcelContext context = MarcelContext.builder().workingDirectory("D:/work/project").build();

        when(runner.resolveBinary("claude")).thenReturn(Optional.empty());

        AgentReport report = agent.execute(task, context);

        assertThat(report.getStatus()).isEqualTo(AgentReport.Status.KO);
        assertThat(report.getSummary()).contains("claude CLI introuvable");
    }

    private CodingAgentsProperties buildProperties() {
        CodingAgentsProperties props = new CodingAgentsProperties();
        props.getClaude().setBinary("claude");
        props.getClaude().setTimeoutMinutes(30);
        return props;
    }
}
