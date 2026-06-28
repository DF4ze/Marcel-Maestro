package fr.ses10doigts.mm.app.specialist.coding;

import java.nio.file.Path;
import java.util.ArrayList;
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

        List<String> args = buildArgs(context, brief);
        ProcessResult result = runner.run(
                binary,
                args,
                Path.of(context.getWorkingDirectory()),
                properties.getClaude().getTimeoutMinutes() * 60);

        log.debug("ClaudeCodeAgent sortie brute — taskId={}, output={}", task.getId(), result.getOutput());
        AgentReport report = reportExtractor.extract(result.getOutput(), result.getExitCode());
        log.info("ClaudeCodeAgent terminé — taskId={}, status={}, exitCode={}",
                task.getId(), report.getStatus(), result.getExitCode());
        return report;
    }

    /**
     * Construit les arguments CLI Claude : mode de permission (pré-autorisation non-interactive)
     * et accès aux workspaces déclarés autres que le répertoire courant ({@code --add-dir}).
     *
     * @param context contexte de mission (workspaces déclarés, répertoire courant)
     * @param brief prompt complet de la mission
     * @return liste ordonnée des arguments passés au binaire Claude
     */
    private List<String> buildArgs(MarcelContext context, String brief) {
        List<String> args = new ArrayList<>();
        args.add("--output-format");
        args.add("text");

        String permissionMode = properties.getClaude().getPermissionMode();
        if (permissionMode != null && !permissionMode.isBlank()) {
            args.add("--permission-mode");
            args.add(permissionMode.trim());
        }

        for (String dir : CliWorkspaceArgs.additionalWorkspaces(context)) {
            args.add("--add-dir");
            args.add(dir);
        }

        args.add("--print");
        args.add(brief);
        return args;
    }
}
