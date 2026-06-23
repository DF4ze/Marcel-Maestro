package fr.ses10doigts.mm.starter.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import fr.ses10doigts.mm.core.tool.AgentTool;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Extension du system prompt qui liste les outils disponibles avec leurs noms exacts.
 *
 * <p>Sans cette extension, le LLM ne connaît pas les noms des outils et en invente
 * ({@code create_file}, {@code file_creation}…), ce qui aboutit à l'erreur
 * "outil inconnu" dans {@link fr.ses10doigts.mm.core.tool.ToolRegistry}.
 * Le LLM produit des {@code tool_calls} en JSON pur ; il doit donc voir le catalogue
 * des outils dans le system prompt pour utiliser les bons noms.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ToolsSystemPromptExtension implements SystemPromptExtension {

    private final List<AgentTool> tools;

    /**
     * Génère un bloc "OUTILS DISPONIBLES" listant chaque outil avec son nom exact,
     * sa description, son niveau de risque et ses paramètres attendus.
     *
     * @return fragment à concaténer au system prompt, vide si aucun outil disponible
     */
    @Override
    public String contribution() {
        if (tools.isEmpty()) {
            log.debug("ToolsSystemPromptExtension — aucun outil, contribution vide");
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("OUTILS DISPONIBLES\n");
        sb.append("Utilise le champ \"tool_calls\" pour déclencher un outil.\n");
        sb.append("Nomme l'outil EXACTEMENT comme indiqué ci-dessous (sensible à la casse).\n");
        sb.append("Les outils de risque HIGH déclenchent une validation humaine avant exécution.\n\n");

        for (AgentTool tool : tools) {
            sb.append("- ").append(tool.name())
              .append(" [").append(tool.riskLevel()).append("]")
              .append(" : ").append(tool.description()).append("\n");

            String paramsHint = buildParamsHint(tool.inputSchema());
            if (paramsHint != null) {
                sb.append("  params: ").append(paramsHint).append("\n");
            }
        }

        log.debug("ToolsSystemPromptExtension — {} outil(s) exposé(s) au LLM", tools.size());
        return sb.toString().strip();
    }

    /**
     * Extrait une description lisible des paramètres depuis le schéma JSON de l'outil.
     *
     * @param schema schéma JSON (peut être null)
     * @return représentation textuelle des params, ou null si le schéma est absent/vide
     */
    private String buildParamsHint(JsonNode schema) {
        if (schema == null) {
            return null;
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || properties.isEmpty()) {
            return null;
        }

        StringBuilder params = new StringBuilder("{");
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode fieldNode = entry.getValue();
            String description = fieldNode.has("description")
                    ? fieldNode.get("description").asText()
                    : fieldName;
            params.append(" \"").append(fieldName).append("\": \"").append(description).append("\"");
            if (fields.hasNext()) {
                params.append(",");
            }
        }
        params.append(" }");
        return params.toString();
    }
}
