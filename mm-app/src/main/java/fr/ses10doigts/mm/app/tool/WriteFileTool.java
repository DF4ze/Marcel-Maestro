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
 * Outil d'écriture de fichier dans le workspace.
 *
 * <p>Écrit du contenu dans un fichier dont le chemin est relatif au workspace configuré.
 * Les répertoires parents sont créés automatiquement si nécessaire.</p>
 *
 * <p>{@code riskLevel = HIGH} : l'écriture de fichier est une opération potentiellement
 * destructive qui nécessite un consentement HITL.</p>
 */
@Slf4j
@Component
public class WriteFileTool implements AgentTool {

    private static final JsonNode SCHEMA = buildSchema();

    private final Path workspaceRoot;

    /**
     * Construit le tool avec le répertoire workspace configuré.
     *
     * @param workspaceRoot chemin racine du workspace (défaut {@code ./workspace})
     */
    public WriteFileTool(@Value("${mm.workspace.root:./workspace}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "write_file";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Écrire du contenu dans un fichier";
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
     * Écrit le contenu dans le fichier spécifié.
     *
     * @param params paramètres validés ({@code path} et {@code content} requis)
     * @param ctx    contexte d'exécution courant
     * @return résultat de succès avec le chemin écrit
     * @throws ToolException si les paramètres sont absents ou l'écriture échoue
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String path = (String) params.get("path");
        String content = (String) params.get("content");

        if (path == null || path.isBlank()) {
            throw new ToolException("Paramètre 'path' requis et non vide");
        }
        if (content == null) {
            throw new ToolException("Paramètre 'content' requis");
        }

        Path resolved = workspaceRoot.resolve(path).normalize();
        log.info("write_file : écriture dans '{}', tenant='{}'", resolved, ctx.tenant());

        try {
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.debug("Répertoires parents créés : {}", parent);
            }

            Files.writeString(resolved, content);
            log.info("Fichier écrit avec succès : {} ({} caractères)", resolved, content.length());
            return ToolResult.ok("Fichier écrit : " + path);
        } catch (IOException e) {
            log.info("Erreur d'écriture du fichier '{}' : {}", resolved, e.getMessage());
            throw new ToolException("Erreur d'écriture : " + e.getMessage(), e);
        }
    }

    private static JsonNode buildSchema() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Chemin du fichier dans le workspace");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "Contenu à écrire");

        schema.putArray("required").add("path").add("content");

        return schema;
    }
}
