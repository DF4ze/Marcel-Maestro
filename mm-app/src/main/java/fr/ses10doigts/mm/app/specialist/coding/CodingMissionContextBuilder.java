package fr.ses10doigts.mm.app.specialist.coding;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.memory.MemoryEntry;
import fr.ses10doigts.mm.core.memory.MemoryStore;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Construit le contexte enrichi d'une mission Claude/Codex à partir du projet courant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodingMissionContextBuilder {

    private static final List<String> PROJECT_FILES = List.of("PROJECT.md", "project.md");
    private static final List<String> ROADMAP_RESULT_FILES =
            List.of("roadmap_result.md", "ROADMAP_RESULT.md", "ROADMAP.md", "roadmap.md");

    private final ProjectRepository projectRepository;
    private final MemoryStore memoryStore;

    /**
     * Charge le projet, ses fichiers de contexte et les faits mémoire pertinents.
     *
     * @param ctx contexte moteur transporté par le {@code TaskMessage}
     * @return contexte Marcel complet prêt à être injecté au spécialiste CLI
     */
    public MarcelContext build(AgentContext ctx) {
        String projectId = ctx.projectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("projectId absent du contexte pour une mission coding");
        }

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException("Projet introuvable pour projectId=" + projectId));
        Path workspacePath = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();

        MarcelContext context = MarcelContext.builder()
                .projectMd(readFirstExistingFile(workspacePath, PROJECT_FILES).orElse(""))
                .roadmapResultMd(readFirstExistingFile(workspacePath, ROADMAP_RESULT_FILES).orElse(""))
                .c3Facts(loadFacts(ctx))
                .workingDirectory(workspacePath.toString())
                .build();

        log.debug("CodingMissionContextBuilder — projectId={}, workingDirectory={}, facts={}",
                projectId, context.getWorkingDirectory(), context.getC3Facts().size());
        return context;
    }

    /**
     * Cherche le premier fichier existant dans l'ordre fourni et renvoie son contenu.
     *
     * @param workspacePath racine du projet courant
     * @param candidateFiles noms de fichiers acceptés
     * @return contenu du premier fichier trouvé, sinon vide
     */
    private Optional<String> readFirstExistingFile(Path workspacePath, List<String> candidateFiles) {
        for (String fileName : candidateFiles) {
            Path candidate = workspacePath.resolve(fileName).normalize();
            if (Files.isRegularFile(candidate)) {
                return Optional.of(readFile(candidate));
            }
        }
        return Optional.empty();
    }

    /**
     * Lit un fichier texte UTF-8 et remonte une erreur explicite si la lecture échoue.
     *
     * @param file fichier à lire
     * @return contenu intégral du fichier
     */
    private String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de lire le fichier de contexte " + file, e);
        }
    }

    /**
     * Agrège les faits globaux et projet, puis élimine les doublons vides.
     *
     * @param ctx contexte moteur courant
     * @return liste stable de faits à injecter dans le mission brief
     */
    private List<String> loadFacts(AgentContext ctx) {
        List<MemoryEntry> globalFacts = memoryStore.findByScope("global", ctx);
        List<MemoryEntry> projectFacts = memoryStore.findByScope("project:" + ctx.projectId(), ctx);

        Set<String> uniqueFacts = new LinkedHashSet<>();
        addFacts(uniqueFacts, globalFacts);
        addFacts(uniqueFacts, projectFacts);
        return List.copyOf(uniqueFacts);
    }

    /**
     * Ajoute les valeurs de mémoire non vides dans l'ensemble de faits.
     *
     * @param target ensemble de déduplication
     * @param entries entrées mémoire à convertir
     */
    private void addFacts(Set<String> target, List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (MemoryEntry entry : entries) {
            if (entry == null || entry.value() == null || entry.value().isBlank()) {
                continue;
            }
            target.add(entry.value().trim());
        }
    }
}
