package fr.ses10doigts.mm.app.specialist.coding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Adaptateur Marcel vers le CLI Codex.
 */
@Component("codex")
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

        List<String> args = buildArgs(context);
        ProcessResult result = runner.run(
                binary,
                args,
                Path.of(context.getWorkingDirectory()),
                properties.getCodex().getTimeoutMinutes() * 60,
                brief);

        log.debug("CodexAgent sortie brute — taskId={}, output={}", task.getId(), result.getOutput());
        AgentReport report = reportExtractor.extract(result.getOutput(), result.getExitCode());
        log.info("CodexAgent terminé — taskId={}, status={}, exitCode={}",
                task.getId(), report.getStatus(), result.getExitCode());
        return report;
    }

    /**
     * Construit les arguments CLI Codex : sandbox + politique d'approbation (pré-autorisation
     * non-interactive) et extension des racines accessibles en écriture aux workspaces déclarés
     * autres que le répertoire courant ({@code sandbox_workspace_write.writable_roots}).
     *
     * <p>Le prompt est passé sur stdin ({@code -}), qui doit rester le dernier argument positionnel.</p>
     *
     * @param context contexte de mission (workspaces déclarés, répertoire courant)
     * @return liste ordonnée des arguments passés au binaire Codex
     */
    private List<String> buildArgs(MarcelContext context) {
        List<String> args = new ArrayList<>();
        args.add("exec");
        args.add("--skip-git-repo-check");

        String sandbox = properties.getCodex().getSandbox();
        if (sandbox != null && !sandbox.isBlank()) {
            args.add("--sandbox");
            args.add(sandbox.trim());
        }

        String approvalPolicy = properties.getCodex().getApprovalPolicy();
        if (approvalPolicy != null && !approvalPolicy.isBlank()) {
            args.add("--ask-for-approval");
            args.add(approvalPolicy.trim());
        }

        List<String> extraRoots = CliWorkspaceArgs.additionalWorkspaces(context);
        if (!extraRoots.isEmpty()) {
            args.add("-c");
            args.add("sandbox_workspace_write.writable_roots=" + toTomlArray(extraRoots));
        }

        args.add("-");
        return args;
    }

    /**
     * Sérialise une liste de chemins en tableau TOML pour {@code -c key=value}, en échappant
     * les antislashs (chemins Windows) et les guillemets.
     *
     * @param paths chemins absolus à inclure
     * @return littéral tableau TOML, ex. {@code ["C:\\repos\\api","C:\\repos\\front"]}
     */
    private String toTomlArray(List<String> paths) {
        return paths.stream()
                .map(path -> "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
