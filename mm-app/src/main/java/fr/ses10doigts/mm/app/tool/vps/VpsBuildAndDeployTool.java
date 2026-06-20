package fr.ses10doigts.mm.app.tool.vps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.core.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Outil agentique pour builder et deployer un projet sur le VPS via la passerelle MCP.
 *
 * <p>Appelle l'outil MCP {@code build_and_deploy} avec les parametres {@code project},
 * {@code branch} et {@code environment}. Niveau de risque {@link RiskLevel#CRITICAL} :
 * un deploiement modifie l'etat de production/staging du VPS.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mm.vps.gateway.enabled", havingValue = "true", matchIfMissing = false)
public class VpsBuildAndDeployTool implements AgentTool {

    private static final JsonNode SCHEMA = buildSchema();
    private final McpSyncClient mcpClient;

    /**
     * Constructeur avec injection du client MCP.
     *
     * @param mcpClient client MCP synchrone pour communiquer avec la passerelle VPS
     */
    public VpsBuildAndDeployTool(McpSyncClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "vps_build_and_deploy";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Build et deploie un projet sur le VPS";
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    /** {@inheritDoc} */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.CRITICAL;
    }

    /** {@inheritDoc} */
    @Override
    public long maxExecutionTimeMs() {
        return 600_000L;
    }

    /**
     * Build et deploie le projet sur l'environnement cible via l'outil MCP {@code build_and_deploy}.
     *
     * @param params parametres valides ({@code project} requis, {@code branch} et {@code environment} optionnels)
     * @param ctx    contexte d'execution courant
     * @return resultat du build et deploiement
     * @throws ToolException en cas d'echec de communication avec la passerelle MCP
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String project = (String) params.get("project");
        String branch = params.getOrDefault("branch", "main").toString();
        String environment = params.getOrDefault("environment", "staging").toString();

        log.info("Lancement du build+deploy VPS pour le projet {} sur la branche {} vers {}", project, branch, environment);
        log.debug("Contexte d'execution : tenant={}, conversationId={}", ctx.tenant(), ctx.conversationId());

        try {
            Map<String, Object> mcpParams = new HashMap<>();
            mcpParams.put("project", project);
            mcpParams.put("branch", branch);
            mcpParams.put("environment", environment);

            log.debug("Appel MCP 'build_and_deploy' avec les parametres : {}", mcpParams);
            CallToolResult result = mcpClient.callTool(new CallToolRequest("build_and_deploy", mcpParams));

            String output = result.content().stream()
                    .filter(TextContent.class::isInstance)
                    .map(c -> ((TextContent) c).text())
                    .collect(Collectors.joining("\n"));

            log.info("Build+deploy VPS termine avec succes pour le projet {} vers {}", project, environment);
            log.debug("Sortie du build+deploy : {}", output);
            return ToolResult.ok(output);
        } catch (Exception e) {
            log.error("Echec du build+deploy VPS pour le projet {} vers {}", project, environment, e);
            return ToolResult.fail("Echec du build+deploy VPS : " + e.getMessage());
        }
    }

    /**
     * Construit le JSON Schema des parametres d'entree de l'outil.
     *
     * @return schema JSON decrivant les parametres {@code project}, {@code branch} et {@code environment}
     */
    private static JsonNode buildSchema() {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("type", "object");

        var properties = root.putObject("properties");

        var project = properties.putObject("project");
        project.put("type", "string");
        project.put("description", "Nom du projet");

        var branch = properties.putObject("branch");
        branch.put("type", "string");
        branch.put("description", "Branche Git");
        branch.put("default", "main");

        var environment = properties.putObject("environment");
        environment.put("type", "string");
        environment.put("description", "Environnement cible (staging, production)");
        environment.put("default", "staging");

        root.putArray("required").add("project");

        return root;
    }
}
