package fr.ses10doigts.mm.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.mm.core.agent.AgentContext;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Convertisseur interne {@link AgentTool} vers {@link ToolCallback} de Spring AI (etape 6).
 *
 * <p>Cree un {@link ToolCallback} a partir d'un {@link AgentTool} en capturant le
 * {@link AgentContext} et le {@link ToolExecutionGuard} au moment de la construction.
 * Quand le LLM invoque le callback, les parametres JSON sont deserialises, les gardes
 * sont appliques, puis l'outil est execute.</p>
 *
 * <p>Le moteur est le seul consommateur de cette classe : l'hote n'implemente
 * que {@link AgentTool}.</p>
 */
@Slf4j
public final class AgentToolConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private AgentToolConverter() {
        // utilitaire, pas instanciable
    }

    /**
     * Convertit un {@link AgentTool} en {@link ToolCallback} Spring AI.
     *
     * @param tool  outil a adapter
     * @param ctx   contexte d'execution capture
     * @param guard garde d'execution transverse
     * @return un {@link ToolCallback} pret a etre enregistre dans le {@code ChatClient}
     */
    public static ToolCallback toCallback(AgentTool tool, AgentContext ctx, ToolExecutionGuard guard) {
        log.info("Conversion AgentTool '{}' -> ToolCallback", tool.name());
        log.debug("Schema de '{}' : {}", tool.name(), tool.inputSchema());
        return new AgentToolCallbackAdapter(tool, ctx, guard);
    }

    /**
     * Adaptateur interne implementant {@link ToolCallback}.
     */
    private static final class AgentToolCallbackAdapter implements ToolCallback {

        private final AgentTool tool;
        private final AgentContext ctx;
        private final ToolExecutionGuard guard;
        private final ToolDefinition toolDefinition;

        AgentToolCallbackAdapter(AgentTool tool, AgentContext ctx, ToolExecutionGuard guard) {
            this.tool = tool;
            this.ctx = ctx;
            this.guard = guard;
            this.toolDefinition = DefaultToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(tool.inputSchema().toString())
                    .build();
        }

        /** {@inheritDoc} */
        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        /** {@inheritDoc} */
        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        /**
         * Appele par Spring AI quand le LLM invoque l'outil.
         *
         * @param toolInput parametres JSON serialises
         * @return resultat JSON serialise
         */
        @Override
        public String call(String toolInput) {
            log.info("Appel de l'outil '{}' avec les parametres bruts", tool.name());
            log.debug("Input brut de '{}' : {}", tool.name(), toolInput);

            Map<String, Object> params;
            try {
                params = MAPPER.readValue(toolInput, MAP_TYPE);
            } catch (JsonProcessingException e) {
                log.info("Echec deserialisation des parametres de '{}' : {}", tool.name(), e.getMessage());
                return serializeResult(ToolResult.fail("invalid input: " + e.getMessage()));
            }

            ToolResult result = guard.execute(tool, params, ctx);
            log.info("Outil '{}' resultat succes={}", tool.name(), result.success());
            return serializeResult(result);
        }

        /** {@inheritDoc} */
        @Override
        public String call(String toolInput, ToolContext toolContext) {
            // Le ToolContext Spring AI n'est pas utilise — le contexte passe par AgentContext
            return call(toolInput);
        }

        private static String serializeResult(ToolResult result) {
            try {
                return MAPPER.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                log.info("Echec serialisation du resultat : {}", e.getMessage());
                return "{\"success\":false,\"data\":null,\"error\":\"serialization error\"}";
            }
        }
    }
}
