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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outil de lecture de fichier dans le workspace.
 *
 * <p>Lit le contenu d'un fichier dont le chemin est relatif au workspace configuré.
 * La taille maximale de lecture est limitée à 100 Ko pour éviter la surcharge mémoire.</p>
 */
@Slf4j
@Component
public class ReadFileTool implements AgentTool {

    private static final long MAX_SIZE_BYTES = 100 * 1024L;
    private static final JsonNode SCHEMA = buildSchema();

    private final Path workspaceRoot;

    /**
     * Construit le tool avec le répertoire workspace configuré.
     *
     * @param workspaceRoot chemin racine du workspace (défaut {@code ./workspace})
     */
    public ReadFileTool(@Value("${mm.workspace.root:./workspace}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "read_file";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Lit le contenu d'un fichier dans le workspace";
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    /** {@inheritDoc} */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    /**
     * Lit le contenu du fichier spécifié par le paramètre {@code path}.
     *
     * @param params paramètres validés ({@code path} requis)
     * @param ctx    contexte d'exécution courant
     * @return résultat contenant le contenu du fichier
     * @throws ToolException si le paramètre est absent ou la lecture échoue
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String path = (String) params.get("path");
        if (path == null || path.isBlank()) {
            throw new ToolException("Paramètre 'path' requis et non vide");
        }

        Path resolved = workspaceRoot.resolve(path).normalize();
        log.info("read_file : lecture de '{}', tenant='{}'", resolved, ctx.tenant());

        if (!Files.exists(resolved)) {
            log.info("Fichier introuvable : {}", resolved);
            return ToolResult.fail("Fichier introuvable : " + path);
        }

        try {
            long size = Files.size(resolved);
            log.debug("Taille du fichier : {} octets", size);

            if (size > MAX_SIZE_BYTES) {
                log.info("Fichier trop volumineux : {} octets (max {} octets)", size, MAX_SIZE_BYTES);
                return ToolResult.fail("Fichier trop volumineux : " + size + " octets (max 100 Ko)");
            }

            String content = Files.readString(resolved);
            log.debug("Fichier lu avec succès : {} caractères", content.length());
            return ToolResult.ok(content);
        } catch (IOException e) {
            log.info("Erreur de lecture du fichier '{}' : {}", resolved, e.getMessage());
            throw new ToolException("Erreur de lecture : " + e.getMessage(), e);
        }
    }

    private static JsonNode buildSchema() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Chemin relatif du fichier dans le workspace");

        schema.putArray("required").add("path");

        return schema;
    }
}
