package fr.ses10doigts.mm.starter.prompt;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Extension du system prompt injectant le contexte projet courant (E3-M0).
 *
 * <p>Lit le {@code projectId} depuis {@link AgentContextHolder}, puis charge le
 * {@link ProjectEntity} associé pour exposer au LLM le nom du projet et le chemin
 * de son workspace interne. En l'absence de contexte projet, aucune contribution
 * n'est ajoutée.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ProjectSystemPromptExtension implements SystemPromptExtension {

    private final AgentContextHolder agentContextHolder;
    private final ProjectRepository projectRepository;

    /**
     * Génère la contribution projet à concaténer au system prompt.
     *
     * @return fragment de contexte projet, ou chaîne vide si aucun projet courant n'est défini
     */
    @Override
    public String contribution() {
        AgentContext context = agentContextHolder.get();
        String projectId = context != null ? context.projectId() : null;
        if (projectId == null || projectId.isBlank()) {
            log.debug("ProjectSystemPromptExtension — aucun projectId dans le contexte courant");
            return "";
        }

        Optional<ProjectEntity> project = projectRepository.findById(projectId);
        if (project.isEmpty()) {
            log.debug("ProjectSystemPromptExtension — projectId={} introuvable", projectId);
            return "";
        }

        ProjectEntity entity = project.get();
        log.info("ProjectSystemPromptExtension — injection du contexte projet {}", projectId);
        return """
                CONTEXTE PROJET COURANT
                - Nom du projet : %s
                - Workspace interne : %s""".formatted(entity.getName(), entity.getWorkspacePath());
    }
}
