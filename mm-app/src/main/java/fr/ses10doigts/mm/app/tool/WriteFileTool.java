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
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outil d'ecriture de fichier dans le workspace.
 */
@Slf4j
@Component
public class WriteFileTool implements AgentTool {

    private static final JsonNode SCHEMA = buildSchema();

    private final Path workspaceRoot;
    private final ProjectRepository projectRepository;
    private final ProjectWorkspaceRepository projectWorkspaceRepository;

    public WriteFileTool(@Value("${mm.workspace.root:./workspace}") String workspaceRoot,
                         ProjectRepository projectRepository,
                         ProjectWorkspaceRepository projectWorkspaceRepository) {
        this.workspaceRoot = Path.of(workspaceRoot);
        this.projectRepository = projectRepository;
        this.projectWorkspaceRepository = projectWorkspaceRepository;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Ecrire du contenu dans un fichier";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException {
        String path = (String) params.get("path");
        String content = (String) params.get("content");

        if (path == null || path.isBlank()) {
            throw new ToolException("Parametre 'path' requis et non vide");
        }
        if (content == null) {
            throw new ToolException("Parametre 'content' requis");
        }

        Path resolved = resolvePath(path, ctx);
        log.info("write_file : ecriture dans '{}', tenant='{}'", resolved, ctx.tenant());

        try {
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.debug("Repertoires parents crees : {}", parent);
            }

            Files.writeString(resolved, content);
            log.info("Fichier ecrit avec succes : {} ({} caracteres)", resolved, content.length());
            return ToolResult.ok("Fichier ecrit : " + path);
        } catch (IOException e) {
            log.info("Erreur d'ecriture du fichier '{}' : {}", resolved, e.getMessage());
            throw new ToolException("Erreur d'ecriture : " + e.getMessage(), e);
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
                    log.info("write_file : redirection fichier contexte projet '{}' -> '{}'",
                            requestedPath, redirected);
                    return redirected;
                } catch (InvalidPathException e) {
                    throw new ToolException("Workspace projet invalide : " + e.getMessage(), e);
                }
            }
        }

        Path projectRelativePath = resolveProjectRelativePath(normalized, ctx);
        if (projectRelativePath != null) {
            Path fallback = resolveAttachedWorkspacePath(ctx, requestedPath);
            if (fallback != null) {
                log.info("write_file : fallback workspace rattache '{}' -> '{}'", requestedPath, fallback);
                return fallback;
            }
            log.info("write_file : chemin relatif projet '{}' -> '{}'", requestedPath, projectRelativePath);
            return projectRelativePath;
        }
        return workspaceRoot.resolve(requestedPath).normalize();
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

    /**
     * Réutilise un fichier déjà présent dans un workspace annexe plutôt que d'écrire ailleurs.
     */
    private Path resolveAttachedWorkspacePath(AgentContext ctx, String requestedPath) throws ToolException {
        if (ctx.projectId() == null || ctx.projectId().isBlank()) {
            return null;
        }
        try {
            return projectWorkspaceRepository.findAllByProjectId(ctx.projectId()).stream()
                    .map(ws -> Path.of(ws.getPath()).toAbsolutePath().normalize().resolve(requestedPath).normalize())
                    .filter(candidate -> Files.exists(candidate) && Files.isRegularFile(candidate))
                    .findFirst()
                    .orElse(null);
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
        path.put("description", "Chemin du fichier dans le workspace");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "Contenu a ecrire");

        schema.putArray("required").add("path").add("content");
        return schema;
    }
}
