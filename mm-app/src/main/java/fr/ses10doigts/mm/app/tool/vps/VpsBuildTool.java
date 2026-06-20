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
 * Outil agentique pour lancer un build sur le VPS via la passerelle MCP.
 *
 * <p>Appelle l'outil MCP {@code build} avec les parametres {@code project} et
 * {@code branch}. Niveau de risque {@link RiskLevel#HIGH} : un build peut
 * consommer des ressources significatives sur le VPS.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mm.vps.gateway.enabled", havingValue = "true", matchIfMissing = false)
public class VpsBuildTool implements AgentTool {

    private static final JsonNode SCHEMA = buildSchema();
    private final McpSyncClient mcpClient;

    /**
     * Constructeur avec injection du client MCP.
     *
     * @param mcpClient client MCP synchrone pour communiquer avec la passerelle VPS
     */
    public VpsBuildTool(McpSyncClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "vps_build";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Lance un build sur le VPS via la passerelle MCP";
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
        return 600_000L;
    }

    /**
     * Lance le build du projet sur la branche indiquee via l'outil MCP {@code build}.
     *
     * @param params parametres valides ({@code project} requis, {@code branch} optionnel)
     * @param ctx    contexte d'execution courant
     * @return resultat du build
     * @throws ToolException en cas d'echec de communication avec la passerelle MCP
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String project = (String) params.get("project");
        String branch = params.getOrDefault("branch", "main").toString();

        log.info("Lancement du build VPS pour le projet {} sur la branche {}", project, branch);
        log.debug("Contexte d'execution : tenant={}, conversationId={}", ctx.tenant(), ctx.conversationId());

        try {
            Map<String, Object> mcpParams = new HashMap<>();
            mcpParams.put("project", project);
            mcpParams.put("branch", branch);

            log.debug("Appel MCP 'build' avec les parametres : {}", mcpParams);
            CallToolResult result = mcpClient.callTool(new CallToolRequest("build", mcpParams));

            String output = result.content().stream()
                    .filter(TextContent.class::isInstance)
                    .map(c -> ((TextContent) c).text())
                    .collect(Collectors.joining("\n"));

            log.info("Build VPS termine avec succes pour le projet {}", project);
            log.debug("Sortie du build : {}", output);
            return ToolResult.ok(output);
        } catch (Exception e) {
            log.error("Echec du build VPS pour le projet {} sur la branche {}", project, branch, e);
            return ToolResult.fail("Echec du build VPS : " + e.getMessage());
        }
    }

    /**
     * Construit le JSON Schema des parametres d'entree de l'outil.
     *
     * @return schema JSON decrivant les parametres {@code project} et {@code branch}
     */
    private static JsonNode buildSchema() {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("type", "object");

        var properties = root.putObject("properties");

        var project = properties.putObject("project");
        project.put("type", "string");
        project.put("description", "Nom du projet a builder");

        var branch = properties.putObject("branch");
        branch.put("type", "string");
        branch.put("description", "Branche Git a builder");
        branch.put("default", "main");

        root.putArray("required").add("project");

        return root;
    }
}
