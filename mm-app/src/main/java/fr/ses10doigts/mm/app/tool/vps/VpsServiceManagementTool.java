package fr.ses10doigts.mm.app.tool.vps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.tool.AgentTool;
import fr.ses10doigts.mm.core.tool.RiskLevel;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.core.tool.ToolResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.McpSyncClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Outil agentique pour gerer les services sur le VPS via la passerelle MCP.
 *
 * <p>Appelle l'outil MCP {@code service_management} avec les parametres {@code service}
 * et {@code action}. Supporte les actions : start, stop, restart, status.
 * Niveau de risque {@link RiskLevel#HIGH} : les actions start/stop/restart impactent
 * directement la disponibilite des services.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mm.vps.gateway.enabled", havingValue = "true", matchIfMissing = false)
public class VpsServiceManagementTool implements AgentTool {

    private static final JsonNode SCHEMA = buildSchema();
    private final McpSyncClient mcpClient;

    /**
     * Constructeur avec injection du client MCP.
     *
     * @param mcpClient client MCP synchrone pour communiquer avec la passerelle VPS
     */
    public VpsServiceManagementTool(McpSyncClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "vps_service_management";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Gere un service sur le VPS (start, stop, restart, status)";
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

    /**
     * Execute l'action demandee sur le service via l'outil MCP {@code service_management}.
     *
     * @param params parametres valides ({@code service} et {@code action} requis)
     * @param ctx    contexte d'execution courant
     * @return resultat de l'action sur le service
     * @throws ToolException en cas d'echec de communication avec la passerelle MCP
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String service = (String) params.get("service");
        String action = (String) params.get("action");

        log.info("Gestion du service VPS : action={} sur le service {}", action, service);
        log.debug("Contexte d'execution : tenant={}, conversationId={}", ctx.tenant(), ctx.conversationId());

        try {
            Map<String, Object> mcpParams = new HashMap<>();
            mcpParams.put("service", service);
            mcpParams.put("action", action);

            log.debug("Appel MCP 'service_management' avec les parametres : {}", mcpParams);
            CallToolResult result = mcpClient.callTool(new CallToolRequest("service_management", mcpParams));

            String output = result.content().stream()
                    .filter(TextContent.class::isInstance)
                    .map(c -> ((TextContent) c).text())
                    .collect(Collectors.joining("\n"));

            log.info("Action {} executee avec succes sur le service {}", action, service);
            log.debug("Sortie de la gestion de service : {}", output);
            return ToolResult.ok(output);
        } catch (Exception e) {
            log.error("Echec de l'action {} sur le service {}", action, service, e);
            return ToolResult.fail("Echec de la gestion du service VPS : " + e.getMessage());
        }
    }

    /**
     * Construit le JSON Schema des parametres d'entree de l'outil.
     *
     * @return schema JSON decrivant les parametres {@code service} et {@code action}
     */
    private static JsonNode buildSchema() {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("type", "object");

        var properties = root.putObject("properties");

        var service = properties.putObject("service");
        service.put("type", "string");
        service.put("description", "Nom du service");

        var action = properties.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("start").add("stop").add("restart").add("status");
        action.put("description", "Action a effectuer");

        root.putArray("required").add("service").add("action");

        return root;
    }
}
