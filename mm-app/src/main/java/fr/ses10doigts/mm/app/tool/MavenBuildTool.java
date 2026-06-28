package fr.ses10doigts.mm.app.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.core.tool.ToolResult;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outil d'exécution de commandes Maven dans le workspace.
 *
 * <p>Lance un processus {@code mvn} avec les goals spécifiés, optionnellement
 * restreint à un module ({@code -pl}). Capture la sortie combinée stdout+stderr
 * et respecte un timeout de 5 minutes.</p>
 *
 * <p>{@code riskLevel = HIGH} : l'exécution de commandes Maven peut modifier
 * le système de fichiers et nécessite un consentement HITL.</p>
 */
@Slf4j
@Component
public class MavenBuildTool implements AgentTool {

    private static final long TIMEOUT_MS = 300_000L;
    private static final JsonNode SCHEMA = buildSchema();

    private final Path workspaceRoot;
    private final ProjectRepository projectRepository;

    /**
     * Construit le tool avec le répertoire workspace configuré.
     *
     * @param workspaceRoot chemin racine du workspace global (défaut {@code ./workspace}),
     *                      utilisé en repli quand aucun projet n'est associé au contexte
     * @param projectRepository repository des projets, pour exécuter Maven dans le workspace
     *                          du projet courant plutôt que dans le workspace global
     */
    public MavenBuildTool(@Value("${mm.workspace.root:./workspace}") String workspaceRoot,
                          ProjectRepository projectRepository) {
        this.workspaceRoot = Path.of(workspaceRoot);
        this.projectRepository = projectRepository;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "maven_build";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Exécute une commande Maven (goals, ex: 'clean verify') dans le workspace du projet courant. "
                + "Le répertoire d'exécution est résolu automatiquement sur le workspace interne du projet ; "
                + "à défaut de projet, le workspace global est utilisé. "
                + "Paramètre 'module' optionnel pour restreindre à un module (-pl).";
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    /** {@inheritDoc} */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    /** {@inheritDoc} */
    @Override
    public long maxExecutionTimeMs() {
        return TIMEOUT_MS;
    }

    /**
     * Exécute la commande Maven avec les goals et le module optionnel spécifiés.
     *
     * @param params paramètres validés ({@code goals} requis, {@code module} optionnel)
     * @param ctx    contexte d'exécution courant
     * @return résultat contenant la sortie combinée stdout+stderr
     * @throws ToolException si les paramètres sont absents ou l'exécution échoue
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String goals = (String) params.get("goals");
        String module = (String) params.get("module");

        if (goals == null || goals.isBlank()) {
            throw new ToolException("Paramètre 'goals' requis et non vide");
        }

        List<String> command = new ArrayList<>();
        command.add("mvn");
        for (String goal : goals.split("\\s+")) {
            command.add(goal);
        }
        if (module != null && !module.isBlank()) {
            command.add("-pl");
            command.add(module);
        }

        Path workingDir = resolveWorkingDirectory(ctx);
        log.info("maven_build : exécution de '{}' dans '{}', tenant='{}'", command, workingDir, ctx.tenant());

        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            log.debug("Processus Maven démarré, PID={}", process.pid());

            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.info("Processus Maven interrompu après timeout de {} ms", TIMEOUT_MS);
                return ToolResult.fail("Timeout Maven dépassé (" + TIMEOUT_MS + " ms)");
            }

            int exitCode = process.exitValue();
            log.info("Maven terminé avec code de sortie {}", exitCode);
            log.debug("Sortie Maven ({} caractères)", output.length());

            if (exitCode != 0) {
                return ToolResult.fail("Maven exit code " + exitCode + "\n" + output);
            }

            return ToolResult.ok(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Exécution Maven interrompue");
            throw new ToolException("Exécution Maven interrompue", e);
        } catch (IOException e) {
            log.info("Erreur d'exécution Maven : {}", e.getMessage());
            throw new ToolException("Erreur d'exécution Maven : " + e.getMessage(), e);
        }
    }

    /**
     * Résout le répertoire d'exécution Maven : workspace interne du projet courant si présent,
     * sinon workspace global de repli.
     *
     * @param ctx contexte d'exécution courant
     * @return répertoire de travail pour le processus Maven
     */
    private Path resolveWorkingDirectory(AgentContext ctx) {
        if (ctx == null || ctx.projectId() == null || ctx.projectId().isBlank()) {
            return workspaceRoot;
        }
        Optional<ProjectEntity> project = projectRepository.findById(ctx.projectId());
        if (project.isEmpty()) {
            return workspaceRoot;
        }
        try {
            return Path.of(project.get().getWorkspacePath()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("maven_build : workspace projet invalide pour projectId={}, repli global",
                    ctx.projectId(), e);
            return workspaceRoot;
        }
    }

    private static JsonNode buildSchema() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode goals = properties.putObject("goals");
        goals.put("type", "string");
        goals.put("description", "Goals Maven (ex: 'clean verify', 'compile')");

        ObjectNode module = properties.putObject("module");
        module.put("type", "string");
        module.put("description", "Module spécifique (-pl), optionnel");

        schema.putArray("required").add("goals");

        return schema;
    }
}
