package fr.ses10doigts.mm.app.specialist.coding;

import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Adaptateur Marcel vers le CLI Codex.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodexAgent implements SpecialistAgentPort {

    private final CrossPlatformRunner runner;
    private final MissionBriefBuilder missionBriefBuilder;
    private final ReportExtractor reportExtractor;
    private final CodingAgentsProperties properties;

    /**
     * Exécute une mission via le binaire Codex configuré.
     *
     * @param task tâche atomique à déléguer
     * @param context contexte projet et mémoire à injecter
     * @return rapport structuré normalisé par Marcel
     */
    @Override
    public AgentReport execute(AgentTask task, MarcelContext context) {
        log.info("CodexAgent démarré — taskId={}", task.getId());

        String binaryName = properties.getCodex().getBinary();
        return runner.resolveBinary(binaryName)
                .map(binary -> executeResolved(binary, task, context))
                .orElseGet(() -> {
                    log.info("CodexAgent terminé — taskId={}, status={}", task.getId(), AgentReport.Status.KO);
                    return AgentReport.ko("codex CLI introuvable");
                });
    }

    private AgentReport executeResolved(Path binary, AgentTask task, MarcelContext context) {
        String brief = missionBriefBuilder.build(task, context);
        log.debug("CodexAgent brief — taskId={}, brief={}", task.getId(), brief);

        ProcessResult result = runner.run(
                binary,
                List.of("exec", "--skip-git-repo-check", "-"),
                Path.of(context.getWorkingDirectory()),
                properties.getCodex().getTimeoutMinutes() * 60,
                brief);

        log.debug("CodexAgent sortie brute — taskId={}, output={}", task.getId(), result.getOutput());
        AgentReport report = reportExtractor.extract(result.getOutput(), result.getExitCode());
        log.info("CodexAgent terminé — taskId={}, status={}, exitCode={}",
                task.getId(), report.getStatus(), result.getExitCode());
        return report;
    }
}
