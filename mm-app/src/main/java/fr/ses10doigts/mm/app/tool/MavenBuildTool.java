package fr.ses10doigts.mm.app.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.core.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * Construit le tool avec le répertoire workspace configuré.
     *
     * @param workspaceRoot chemin racine du workspace (défaut {@code ./workspace})
     */
    public MavenBuildTool(@Value("${mm.workspace.root:./workspace}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "maven_build";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Exécute une commande Maven dans le workspace";
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

        log.info("maven_build : exécution de '{}', tenant='{}'", command, ctx.tenant());

        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workspaceRoot.toFile())
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
