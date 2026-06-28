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

@Slf4j
@Component
public class ReadFileTool implements AgentTool {

    private static final long MAX_SIZE_BYTES = 100 * 1024L;
    private static final JsonNode SCHEMA = buildSchema();

    private final Path workspaceRoot;
    private final ProjectRepository projectRepository;
    private final ProjectWorkspaceRepository projectWorkspaceRepository;

    public ReadFileTool(@Value("${mm.workspace.root:./workspace}") String workspaceRoot,
                        ProjectRepository projectRepository,
                        ProjectWorkspaceRepository projectWorkspaceRepository) {
        this.workspaceRoot = Path.of(workspaceRoot);
        this.projectRepository = projectRepository;
        this.projectWorkspaceRepository = projectWorkspaceRepository;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Lit le contenu d'un fichier (max 100 Ko). Chemin relatif résolu dans le workspace "
                + "interne du projet courant, puis recherché dans les workspaces rattachés en fallback ; "
                + "chemin absolu sous un workspace déclaré accepté. PROJECT.md et ROADMAP.md sont "
                + "automatiquement redirigés vers le workspace interne du projet.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String path = (String) params.get("path");
        if (path == null || path.isBlank()) {
            throw new ToolException("Parametre 'path' requis et non vide");
        }

        Path resolved = resolvePath(path, ctx);
        log.info("read_file : lecture de '{}', tenant='{}'", resolved, ctx.tenant());

        if (!Files.exists(resolved)) {
            log.info("Fichier introuvable : {}", resolved);
            return ToolResult.fail("Fichier introuvable : " + path);
        }

        try {
            long size = Files.size(resolved);
            if (size > MAX_SIZE_BYTES) {
                log.info("Fichier trop volumineux : {} octets (max {} octets)", size, MAX_SIZE_BYTES);
                return ToolResult.fail("Fichier trop volumineux : " + size + " octets (max 100 Ko)");
            }

            String content = Files.readString(resolved);
            return ToolResult.ok(content);
        } catch (IOException e) {
            log.info("Erreur de lecture du fichier '{}' : {}", resolved, e.getMessage());
            throw new ToolException("Erreur de lecture : " + e.getMessage(), e);
        }
    }

    private Path resolvePath(String requestedPath, AgentContext ctx) throws ToolException {
        String normalized = requestedPath.replace('\\', '/');
        if (ctx.projectId() != null && isProjectContextFile(normalized)) {
            Optional<ProjectEntity> project = projectRepository.findById(ctx.projectId());
            if (project.isPresent()) {
                try {
                    Path projectWorkspace = Path.of(project.get().getWorkspacePath()).toAbsolutePath().normalize();
                    Path redirected = projectWorkspace.resolve(fileNameOf(normalized)).normalize();
                    log.info("read_file : redirection fichier contexte projet '{}' -> '{}'",
                            requestedPath, redirected);
                    return redirected;
                } catch (InvalidPathException e) {
                    throw new ToolException("Workspace projet invalide : " + e.getMessage(), e);
                }
            }
        }

        Path projectRelativePath = resolveProjectRelativePath(normalized, ctx);
        if (projectRelativePath != null) {
            if (Files.exists(projectRelativePath)) {
                log.info("read_file : chemin relatif projet '{}' -> '{}'", requestedPath, projectRelativePath);
                return projectRelativePath;
            }
            Path fallback = resolveInAttachedWorkspaces(ctx.projectId(), requestedPath);
            if (fallback != null) {
                log.info("read_file : fallback workspace rattache '{}' -> '{}'", requestedPath, fallback);
                return fallback;
            }
            return projectRelativePath;
        }

        Path primary = workspaceRoot.resolve(requestedPath).normalize();
        if (ctx.projectId() != null && !Files.exists(primary)) {
            Path fallback = resolveInAttachedWorkspaces(ctx.projectId(), requestedPath);
            if (fallback != null) {
                log.info("read_file : fallback workspace rattache '{}' -> '{}'", requestedPath, fallback);
                return fallback;
            }
        }
        return primary;
    }

    /**
     * Résout un chemin relatif simple dans le workspace du projet courant.
     *
     * @param normalizedPath chemin demandé avec séparateurs normalisés
     * @param ctx contexte d'exécution courant
     * @return chemin projet résolu, ou {@code null} si la règle ne s'applique pas
     * @throws ToolException si le workspace projet est invalide
     */
    private Path resolveProjectRelativePath(String normalizedPath, AgentContext ctx) throws ToolException {
        if (ctx.projectId() == null || ctx.projectId().isBlank()) {
            return null;
        }
        Path requested;
        try {
            requested = Path.of(normalizedPath);
        } catch (InvalidPathException e) {
            throw new ToolException("Chemin invalide : " + normalizedPath, e);
        }
        if (requested.isAbsolute()) {
            return null;
        }

        Optional<ProjectEntity> project = projectRepository.findById(ctx.projectId());
        if (project.isEmpty()) {
            return null;
        }

        try {
            Path projectWorkspace = Path.of(project.get().getWorkspacePath()).toAbsolutePath().normalize();
            return projectWorkspace.resolve(requested).normalize();
        } catch (InvalidPathException e) {
            throw new ToolException("Workspace projet invalide : " + e.getMessage(), e);
        }
    }

    private Path resolveInAttachedWorkspaces(String projectId, String requestedPath) throws ToolException {
        try {
            List<Path> candidates = projectWorkspaceRepository.findAllByProjectId(projectId).stream()
                    .map(ws -> Path.of(ws.getPath()).toAbsolutePath().normalize().resolve(requestedPath).normalize())
                    .toList();
            for (Path candidate : candidates) {
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        } catch (InvalidPathException e) {
            throw new ToolException("Workspace rattache invalide : " + e.getMessage(), e);
        }
    }

    private static boolean isProjectContextFile(String path) {
        String lowered = path.toLowerCase();
        return lowered.equals("project.md")
                || lowered.equals("roadmap.md")
                || lowered.equals("workspace/project.md")
                || lowered.equals("workspace/roadmap.md");
    }

    private static String fileNameOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
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
