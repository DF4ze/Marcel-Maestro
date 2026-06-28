package fr.ses10doigts.mm.app.rest;

import fr.ses10doigts.mm.app.specialist.coding.AgentReport;
import fr.ses10doigts.mm.app.specialist.coding.AgentTask;
import fr.ses10doigts.mm.app.specialist.coding.ClaudeCodeAgent;
import fr.ses10doigts.mm.app.specialist.coding.CodexAgent;
import fr.ses10doigts.mm.app.specialist.coding.CodingAgentsProperties;
import fr.ses10doigts.mm.app.specialist.coding.CrossPlatformRunner;
import fr.ses10doigts.mm.app.specialist.coding.MarcelContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint manuel de validation des adaptateurs Claude/Codex.
 *
 * <p>Ce contrôleur n'est actif qu'avec le profil Spring
 * {@code manual-coding-agent-test}. Il sert uniquement à piloter les
 * agents CLI depuis Postman sans exposer ces endpoints en exécution normale.</p>
 */
@RestController
@RequestMapping("/internal/manual/coding-agents")
@Profile("manual-coding-agent-test")
@RequiredArgsConstructor
@Slf4j
public class ManualCodingAgentController {

    private final ClaudeCodeAgent claudeCodeAgent;
    private final CodexAgent codexAgent;
    private final CrossPlatformRunner runner;
    private final CodingAgentsProperties properties;

    /**
     * Retourne l'état de prévol des deux agents CLI configurés.
     *
     * @return configuration, timeout et résolution effective des binaires
     */
    @GetMapping("/preflight")
    public ResponseEntity<PreflightResponse> preflight() {
        AgentBinaryStatus claude = toStatus("claude", properties.getClaude().getBinary(),
                properties.getClaude().getTimeoutMinutes());
        AgentBinaryStatus codex = toStatus("codex", properties.getCodex().getBinary(),
                properties.getCodex().getTimeoutMinutes());
        log.info("ManualCodingAgentController preflight — claudeResolved={}, codexResolved={}",
                claude.resolved(), codex.resolved());
        return ResponseEntity.ok(new PreflightResponse(claude, codex));
    }

    /**
     * Exécute manuellement une mission contre Claude Code ou Codex.
     *
     * @param agentId identifiant logique d'agent : {@code claude} ou {@code codex}
     * @param request payload de mission et de contexte projet
     * @return rapport structuré normalisé produit par l'agent choisi
     */
    @PostMapping("/{agentId}/execute")
    public ResponseEntity<AgentReport> execute(@PathVariable String agentId,
                                               @RequestBody ExecuteAgentRequest request) {
        AgentTask task = AgentTask.builder()
                .id(request.taskId() != null && !request.taskId().isBlank()
                        ? request.taskId().trim()
                        : UUID.randomUUID().toString())
                .title(request.title())
                .description(request.description())
                .build();
        MarcelContext context = MarcelContext.builder()
                .projectMd(request.projectMd())
                .roadmapResultMd(request.roadmapResultMd())
                .c3Facts(request.c3Facts() == null ? List.of() : request.c3Facts())
                .workingDirectory(request.workingDirectory())
                .build();

        log.info("ManualCodingAgentController execute — agentId={}, taskId={}", agentId, task.getId());
        AgentReport report = switch (agentId.toLowerCase()) {
            case "claude" -> claudeCodeAgent.execute(task, context);
            case "codex" -> codexAgent.execute(task, context);
            default -> AgentReport.ko("agentId inconnu : " + agentId);
        };
        return ResponseEntity.ok(report);
    }

    private AgentBinaryStatus toStatus(String agentId, String configuredBinary, int timeoutMinutes) {
        Optional<Path> resolved = runner.resolveBinary(configuredBinary);
        return new AgentBinaryStatus(
                agentId,
                configuredBinary,
                timeoutMinutes,
                resolved.isPresent(),
                resolved.map(Path::toString).orElse(null));
    }

    /**
     * Payload manuel d'exécution d'un agent CLI spécialiste.
     *
     * @param taskId identifiant de tâche optionnel ; généré si absent
     * @param title titre métier synthétique de la mission
     * @param description description détaillée envoyée à l'agent
     * @param projectMd contenu de PROJECT.md injecté dans le brief
     * @param roadmapResultMd contenu de roadmap_result.md injecté dans le brief
     * @param c3Facts faits mémoire C3 sélectionnés
     * @param workingDirectory répertoire de travail cible du CLI
     */
    public record ExecuteAgentRequest(
            String taskId,
            String title,
            String description,
            String projectMd,
            String roadmapResultMd,
            List<String> c3Facts,
            String workingDirectory) {
    }

    /**
     * Rapport de prévol des deux agents CLI manuels.
     *
     * @param claude statut de résolution de Claude Code
     * @param codex statut de résolution de Codex
     */
    public record PreflightResponse(AgentBinaryStatus claude, AgentBinaryStatus codex) {
    }

    /**
     * Statut de résolution d'un binaire CLI configuré.
     *
     * @param agentId identifiant logique d'agent
     * @param configuredBinary valeur configurée dans {@code mm.agents.*.binary}
     * @param timeoutMinutes timeout configuré
     * @param resolved vrai si le binaire est trouvé
     * @param resolvedPath chemin absolu résolu, ou {@code null} si introuvable
     */
    public record AgentBinaryStatus(
            String agentId,
            String configuredBinary,
            int timeoutMinutes,
            boolean resolved,
            String resolvedPath) {
    }
}
