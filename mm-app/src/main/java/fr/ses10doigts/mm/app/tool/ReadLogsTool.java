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
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outil de lecture des dernières lignes d'un fichier de log.
 *
 * <p>Lit les N dernières lignes d'un fichier dont le chemin est relatif au workspace.
 * Utile pour consulter rapidement les logs applicatifs sans charger le fichier entier.</p>
 */
@Slf4j
@Component
public class ReadLogsTool implements AgentTool {

    private static final int DEFAULT_LINES = 50;
    private static final JsonNode SCHEMA = buildSchema();

    private final Path workspaceRoot;
    private final ProjectRepository projectRepository;
    private final ProjectWorkspaceRepository projectWorkspaceRepository;

    /**
     * Construit le tool avec le répertoire workspace configuré.
     *
     * @param workspaceRoot chemin racine du workspace global (défaut {@code ./workspace}),
     *                      utilisé en repli
     * @param projectRepository repository des projets, pour résoudre les chemins relatifs
     *                          dans le workspace interne du projet courant
     * @param projectWorkspaceRepository repository des workspaces rattachés, pour la
     *                                   résolution multi-dossier en fallback
     */
    public ReadLogsTool(@Value("${mm.workspace.root:./workspace}") String workspaceRoot,
                        ProjectRepository projectRepository,
                        ProjectWorkspaceRepository projectWorkspaceRepository) {
        this.workspaceRoot = Path.of(workspaceRoot);
        this.projectRepository = projectRepository;
        this.projectWorkspaceRepository = projectWorkspaceRepository;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "read_logs";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Lit les N dernières lignes d'un fichier de log (paramètre 'lines', défaut 50). "
                + "Chemin relatif résolu dans le workspace interne du projet courant, avec recherche "
                + "dans les workspaces rattachés en fallback ; chemin absolu accepté tel quel.";
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
     * Lit les dernières lignes du fichier de log spécifié.
     *
     * @param params paramètres validés ({@code path} requis, {@code lines} optionnel)
     * @param ctx    contexte d'exécution courant
     * @return résultat contenant les dernières lignes du fichier
     * @throws ToolException si le paramètre path est absent ou la lecture échoue
     */
    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String path = (String) params.get("path");
        if (path == null || path.isBlank()) {
            throw new ToolException("Paramètre 'path' requis et non vide");
        }

        int lines = DEFAULT_LINES;
        Object linesParam = params.get("lines");
        if (linesParam instanceof Number number) {
            lines = number.intValue();
        }

        Path resolved = resolvePath(path, ctx);
        log.info("read_logs : lecture des {} dernières lignes de '{}', tenant='{}'",
                lines, resolved, ctx.tenant());

        if (!Files.exists(resolved)) {
            log.info("Fichier de log introuvable : {}", resolved);
            return ToolResult.fail("Fichier introuvable : " + path);
        }

        try {
            List<String> allLines = Files.readAllLines(resolved);
            log.debug("Fichier contient {} lignes au total", allLines.size());

            int from = Math.max(0, allLines.size() - lines);
            List<String> tail = allLines.subList(from, allLines.size());

            String result = String.join("\n", tail);
            log.debug("Retour de {} lignes (depuis la ligne {})", tail.size(), from);
            return ToolResult.ok(result);
        } catch (IOException e) {
            log.info("Erreur de lecture du fichier '{}' : {}", resolved, e.getMessage());
            throw new ToolException("Erreur de lecture : " + e.getMessage(), e);
        }
    }

    /**
     * Résout le chemin du log : projet-relatif (workspace interne) avec recherche dans les
     * workspaces rattachés en fallback, sinon repli sur le workspace global. Un chemin absolu
     * est retourné tel quel (normalisé).
     *
     * @param requestedPath chemin demandé
     * @param ctx contexte d'exécution courant
     * @return chemin résolu pour lecture
     */
    private Path resolvePath(String requestedPath, AgentContext ctx) {
        Path requested;
        try {
            requested = Path.of(requestedPath.replace('\\', '/'));
        } catch (InvalidPathException e) {
            return workspaceRoot.resolve(requestedPath).normalize();
        }
        if (requested.isAbsolute()) {
            return requested.normalize();
        }
        if (ctx != null && ctx.projectId() != null && !ctx.projectId().isBlank()) {
            Optional<ProjectEntity> project = projectRepository.findById(ctx.projectId());
            if (project.isPresent()) {
                Path projectPrimary = Path.of(project.get().getWorkspacePath())
                        .toAbsolutePath().normalize().resolve(requested).normalize();
                if (Files.exists(projectPrimary)) {
                    return projectPrimary;
                }
                Path attached = projectWorkspaceRepository.findAllByProjectId(ctx.projectId()).stream()
                        .map(ws -> Path.of(ws.getPath()).toAbsolutePath().normalize().resolve(requested).normalize())
                        .filter(Files::exists)
                        .findFirst()
                        .orElse(null);
                if (attached != null) {
                    return attached;
                }
                return projectPrimary;
            }
        }
        return workspaceRoot.resolve(requested).normalize();
    }

    private static JsonNode buildSchema() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Chemin du fichier de log");

        ObjectNode lines = properties.putObject("lines");
        lines.put("type", "integer");
        lines.put("description", "Nombre de lignes à lire (défaut 50)");
        lines.put("default", DEFAULT_LINES);

        schema.putArray("required").add("path");

        return schema;
    }
}
