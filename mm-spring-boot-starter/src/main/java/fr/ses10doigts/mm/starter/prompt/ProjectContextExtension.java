package fr.ses10doigts.mm.starter.prompt;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import fr.ses10doigts.mm.core.tool.PathValidator;
import fr.ses10doigts.mm.core.tool.ToolException;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
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
 * {@link ProjectEntity} associé, puis ajoute au prompt le contenu tronqué de
 * {@code PROJECT.md} et {@code ROADMAP.md} lorsqu'ils existent dans le workspace du projet.
 * Les variantes historiques en minuscules restent tolérées pour compatibilité.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ProjectContextExtension implements SystemPromptExtension {

    private static final List<String> PROJECT_FILES = List.of("PROJECT.md", "project.md");
    private static final List<String> ROADMAP_FILES = List.of("ROADMAP.md", "roadmap.md");
    private static final String TRUNCATION_SUFFIX = "\n[... contenu tronqué]";

    private final AgentContextHolder agentContextHolder;
    private final ProjectRepository projectRepository;
    private final PathValidator pathValidator;
    private final int maxCharsPerFile;

    /**
     * Génère la contribution projet enrichie à concaténer au system prompt.
     *
     * @return fragment de contexte projet, ou chaîne vide si aucun fichier exploitable n'est présent
     */
    @Override
    public String contribution() {
        AgentContext context = agentContextHolder.get();
        String projectId = context != null ? context.projectId() : null;
        if (projectId == null || projectId.isBlank()) {
            log.debug("ProjectContextExtension — aucun projectId dans le contexte courant");
            return "";
        }

        Optional<ProjectEntity> project = projectRepository.findById(projectId);
        if (project.isEmpty()) {
            log.debug("ProjectContextExtension — projectId={} introuvable", projectId);
            return "";
        }

        Path workspacePath;
        try {
            workspacePath = Path.of(project.get().getWorkspacePath()).normalize();
        } catch (InvalidPathException e) {
            log.warn("ProjectContextExtension — workspacePath invalide pour projectId={}: {}",
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

    private void addSectionIfPresent(List<String> sections, Path workspacePath,
                                     String projectId, List<String> candidateFileNames) {
        for (String fileName : candidateFileNames) {
            String content = readProjectFile(workspacePath, projectId, fileName);
            if (content != null && !content.isBlank()) {
                sections.add("### " + fileName + "\n" + content);
                return;
            }
        }
    }

    private String readProjectFile(Path workspacePath, String projectId, String fileName) {
        Path resolvedPath = workspacePath.resolve(fileName).normalize();
        if (!resolvedPath.startsWith(workspacePath)) {
            log.warn("ProjectContextExtension — chemin rejeté hors workspace projet: {}", resolvedPath);
            return "";
        }
        try {
            pathValidator.validatePath(resolvedPath.toString(), projectId);
        } catch (ToolException e) {
            log.warn("ProjectContextExtension — validation refusée pour {}", resolvedPath, e);
            return "";
        }

        if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            return "";
        }

        try {
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            String truncated = truncateContent(content, fileName, projectId);
            log.debug("ProjectContextExtension — fichier lu: path={}, projectId={}, lengthBefore={}, lengthAfter={}",
                    resolvedPath, projectId, content.length(), truncated.length());
            return truncated;
        } catch (IOException e) {
            log.warn("ProjectContextExtension — lecture impossible pour {}", resolvedPath, e);
            return "";
        }
    }

    private String truncateContent(String content, String fileName, String projectId) {
        if (content.length() <= maxCharsPerFile) {
            return content;
        }
        int endIndex = Math.min(content.length(), maxCharsPerFile);
        log.info("ProjectContextExtension — troncature appliquée: projectId={}, file={}, originalLength={}, maxChars={}",
                projectId, fileName, content.length(), maxCharsPerFile);
        return content.substring(0, endIndex) + TRUNCATION_SUFFIX;
    }
}
