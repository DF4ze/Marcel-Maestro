package fr.ses10doigts.mm.starter.prompt;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Extension du system prompt injectant le contenu des fichiers projet courants (E3-M3).
 *
 * <p>Lit le {@code projectId} depuis {@link AgentContextHolder}, charge le
 * {@link ProjectEntity} associe, puis ajoute au prompt le contenu tronque de
 * {@code PROJECT.md} et {@code ROADMAP.md} lorsqu'ils existent dans le workspace du projet
 * ou dans un workspace annexe rattache. Les variantes historiques en minuscules restent
 * tolerees pour compatibilite.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ProjectContextExtension implements SystemPromptExtension {

    private static final List<String> PROJECT_FILES = List.of("PROJECT.md", "project.md");
    private static final List<String> ROADMAP_FILES = List.of("ROADMAP.md", "roadmap.md");
    private static final String TRUNCATION_SUFFIX = "\n[... contenu tronque]";
    private static final String BOOTSTRAP_NOTE_PREFIX = "### Réponse utilisateur";

    private final AgentContextHolder agentContextHolder;
    private final ProjectRepository projectRepository;
    private final ProjectWorkspaceRepository projectWorkspaceRepository;
    private final PathValidator pathValidator;
    private final int maxCharsPerFile;

    /**
     * Genere la contribution projet enrichie a concatener au system prompt.
     *
     * @return fragment de contexte projet, ou chaine vide si aucun fichier exploitable n'est present
     */
    @Override
    public String contribution() {
        AgentContext context = agentContextHolder.get();
        String projectId = context != null ? context.projectId() : null;
        if (projectId == null || projectId.isBlank()) {
            log.debug("ProjectContextExtension - aucun projectId dans le contexte courant");
            return "";
        }

        Optional<ProjectEntity> project = projectRepository.findById(projectId);
        if (project.isEmpty()) {
            log.debug("ProjectContextExtension - projectId={} introuvable", projectId);
            return "";
        }

        Path workspacePath;
        try {
            workspacePath = Path.of(project.get().getWorkspacePath()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("ProjectContextExtension - workspacePath invalide pour projectId={}: {}",
                    projectId, project.get().getWorkspacePath(), e);
            return "";
        }

        List<String> sections = new ArrayList<>();
        addSectionIfPresent(sections, workspacePath, projectId, PROJECT_FILES);
        addSectionIfPresent(sections, workspacePath, projectId, ROADMAP_FILES);
        if (sections.isEmpty()) {
            return "";
        }
        return "## Contexte projet courant\n\n" + String.join("\n\n", sections);
    }

    /**
     * Ajoute la premiere variante de fichier projet disponible.
     */
    private void addSectionIfPresent(List<String> sections, Path workspacePath,
                                     String projectId, List<String> candidateFileNames) {
        for (String fileName : candidateFileNames) {
            String content = readProjectFile(workspacePath.resolve(fileName).normalize(), projectId, fileName);
            if ((content == null || content.isBlank()) && projectId != null && !projectId.isBlank()) {
                content = readFromAttachedWorkspaces(projectId, fileName);
            }
            if (content != null && !content.isBlank()) {
                sections.add("### " + fileName + "\n" + content);
                return;
            }
        }
    }

    /**
     * Lit un fichier de contexte deja resolu en absolu.
     */
    private String readProjectFile(Path resolvedPath, String projectId, String fileName) {
        try {
            pathValidator.validatePath(resolvedPath.toString(), projectId);
        } catch (ToolException e) {
            log.warn("ProjectContextExtension - validation refusee pour {}", resolvedPath, e);
            return "";
        }

        if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            return "";
        }

        try {
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            String sanitized = sanitizeProjectContext(content, fileName);
            String truncated = truncateContent(sanitized, fileName, projectId);
            log.debug("ProjectContextExtension - fichier lu: path={}, projectId={}, lengthBefore={}, lengthAfter={}",
                    resolvedPath, projectId, sanitized.length(), truncated.length());
            return truncated;
        } catch (IOException e) {
            log.warn("ProjectContextExtension - lecture impossible pour {}", resolvedPath, e);
            return "";
        }
    }

    /**
     * Cherche le fichier de contexte dans les workspaces annexes du projet.
     */
    private String readFromAttachedWorkspaces(String projectId, String fileName) {
        for (Path attachedRoot : attachedWorkspaceRoots(projectId)) {
            String content = readProjectFile(attachedRoot.resolve(fileName).normalize(), projectId, fileName);
            if (content != null && !content.isBlank()) {
                log.info("ProjectContextExtension - fallback workspace rattache: projectId={}, file={}, root={}",
                        projectId, fileName, attachedRoot);
                return content;
            }
        }
        return "";
    }

    /**
     * Retourne les racines des workspaces annexes declares pour le projet.
     */
    private List<Path> attachedWorkspaceRoots(String projectId) {
        return projectWorkspaceRepository.findAllByProjectId(projectId).stream()
                .map(workspace -> Path.of(workspace.getPath()).toAbsolutePath().normalize())
                .toList();
    }

    private String sanitizeProjectContext(String content, String fileName) {
        if (!PROJECT_FILES.contains(fileName)) {
            return content;
        }
        return stripBootstrapNotes(content);
    }

    private String stripBootstrapNotes(String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder sanitized = new StringBuilder();
        boolean skippingNoteBlock = false;
        for (String line : lines) {
            if (line.startsWith(BOOTSTRAP_NOTE_PREFIX)) {
                skippingNoteBlock = true;
                continue;
            }
            if (skippingNoteBlock && line.startsWith("### ")) {
                skippingNoteBlock = false;
            }
            if (skippingNoteBlock) {
                continue;
            }
            if (sanitized.length() > 0) {
                sanitized.append('\n');
            }
            sanitized.append(line);
        }
        return sanitized.toString().trim();
    }

    private String truncateContent(String content, String fileName, String projectId) {
        if (content.length() <= maxCharsPerFile) {
            return content;
        }
        int endIndex = Math.min(content.length(), maxCharsPerFile);
        log.info("ProjectContextExtension - troncature appliquee: projectId={}, file={}, originalLength={}, maxChars={}",
                projectId, fileName, content.length(), maxCharsPerFile);
        return content.substring(0, endIndex) + TRUNCATION_SUFFIX;
    }
}
