package fr.ses10doigts.mm.app.specialist.coding;

import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Adaptateur Marcel vers le CLI Claude Code.
 */
@Component("claude")
@RequiredArgsConstructor
@Slf4j
public class ClaudeCodeAgent implements SpecialistAgentPort {

    private final CrossPlatformRunner runner;
    private final MissionBriefBuilder missionBriefBuilder;
    private final ReportExtractor reportExtractor;
    private final CodingAgentsProperties properties;

    /**
     * Exécute une mission via le binaire Claude Code configuré.
     *
     * @param task tâche atomique à déléguer
     * @param context contexte projet et mémoire à injecter
     * @return rapport structuré normalisé par Marcel
     */
    @Override
    public AgentReport execute(AgentTask task, MarcelContext context) {
        log.info("ClaudeCodeAgent démarré — taskId={}", task.getId());

        String binaryName = properties.getClaude().getBinary();
        return runner.resolveBinary(binaryName)
                .map(binary -> executeResolved(binary, task, context))
                .orElseGet(() -> {
                    log.info("ClaudeCodeAgent terminé — taskId={}, status={}", task.getId(), AgentReport.Status.KO);
                    return AgentReport.ko("claude CLI introuvable");
                });
    }

    private AgentReport executeResolved(Path binary, AgentTask task, MarcelContext context) {
        String brief = missionBriefBuilder.build(task, context);
        log.debug("ClaudeCodeAgent brief — taskId={}, brief={}", task.getId(), brief);

        ProcessResult result = runner.run(
                binary,
                List.of("--output-format", "text", "--print", brief),
                Path.of(context.getWorkingDirectory()),
                properties.getClaude().getTimeoutMinutes() * 60);

        log.debug("ClaudeCodeAgent sortie brute — taskId={}, output={}", task.getId(), result.getOutput());
        AgentReport report = reportExtractor.extract(result.getOutput(), result.getExitCode());
        log.info("ClaudeCodeAgent terminé — taskId={}, status={}, exitCode={}",
                task.getId(), report.getStatus(), result.getExitCode());
        return report;
    }
}
