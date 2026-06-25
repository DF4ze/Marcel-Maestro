package fr.ses10doigts.mm.core.tool;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.HitlGuard;
import fr.ses10doigts.mm.core.hitl.HitlVerdict;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * Couche de sécurité transverse enveloppant chaque exécution d'outil (étape 6, E2-M3+).
 *
 * <p>Applique séquentiellement :</p>
 * <ol>
 *   <li><strong>Rejet système (ADR-023)</strong> : tout chemin absolu pointant vers un
 *       répertoire système (détecté par {@link SystemPathGuard}) est rejeté <em>avant</em>
 *       toute consultation HITL. Aucun bypass n'est possible pour les chemins système.</li>
 *   <li><strong>Bypass workspace déclaré (ADR-023)</strong> : si un {@link WorkspaceRegistry}
 *       est présent et qu'au moins un paramètre chemin appartient à un dossier externe déclaré
 *       pour le projet courant, le HITL write est bypassé structurellement.</li>
 *   <li><strong>HITL</strong> : si pas de bypass, et si un {@link HitlGuard} est présent,
 *       vérifie le consentement humain pour les chemins non-système hors workspace.
 *       Un refus retourne un {@link ToolResult#fail} sans exécuter l'outil.
 *       En cas d'approbation, le flag {@code hitlApproved} est transmis à
 *       {@link PathValidator} pour autoriser le chemin.</li>
 *   <li><strong>Path validation</strong> : si un {@link PathValidator} est présent, valide
 *       les chemins selon la politique complète (voir sa JavaDoc). Le flag
 *       {@code hitlApproved || bypassHitl} est transmis pour ne pas bloquer les chemins
 *       qui ont été approuvés humainement.</li>
 *   <li><strong>Timeout</strong> : exécute l'outil dans un {@link CompletableFuture} borné
 *       par {@link AgentTool#maxExecutionTimeMs()}.</li>
 * </ol>
 *
 * <p>Conçu pour être instancié une fois par le moteur et partagé entre tous les outils.
 * Les gardes (HITL, path, workspace) sont optionnels ({@code null}-safe).</p>
 */
@Slf4j
public class ToolExecutionGuard {

    private final HitlGuard hitlGuard;
    private final PathValidator pathValidator;
    private final WorkspaceRegistry workspaceRegistry;

    /**
     * Constructeur complet.
     *
     * @param hitlGuard         garde HITL ; peut être {@code null}
     * @param pathValidator     validateur de chemins ; peut être {@code null}
     * @param workspaceRegistry registre des dossiers externes ; peut être {@code null}
     */
    public ToolExecutionGuard(HitlGuard hitlGuard, PathValidator pathValidator,
                              WorkspaceRegistry workspaceRegistry) {
        this.hitlGuard = hitlGuard;
        this.pathValidator = pathValidator;
        this.workspaceRegistry = workspaceRegistry;
    }

    /**
     * Constructeur sans registre de workspaces (pas de bypass ADR-023).
     *
     * <p>Utilisé dans les tests unitaires existants de mm-core.</p>
     *
     * @param hitlGuard     garde HITL ; peut être {@code null}
     * @param pathValidator validateur de chemins ; peut être {@code null}
     */
    public ToolExecutionGuard(HitlGuard hitlGuard, PathValidator pathValidator) {
        this(hitlGuard, pathValidator, null);
    }

    /**
     * Exécute un outil avec les gardes de sécurité.
     *
     * @param tool   outil à exécuter
     * @param params paramètres désérialisés
     * @param ctx    contexte d'exécution
     * @return résultat de l'exécution (succès ou échec)
     */
    public ToolResult execute(AgentTool tool, Map<String, Object> params, AgentContext ctx) {
        String toolName = tool.name();

        // 1. Chemins système → rejet immédiat, aucun bypass possible
        Optional<String> dangerousPath = findDangerousAbsolutePath(params);
        if (dangerousPath.isPresent()) {
            log.warn("Outil '{}' — chemin système dangereux '{}', rejet immédiat (pas de HITL)",
                    toolName, dangerousPath.get());
            return ToolResult.fail("path violation: chemin système interdit '" + dangerousPath.get() + "'");
        }

        // 2. Bypass HITL write si chemin dans un workspace externe déclaré (ADR-023)
        boolean bypassHitl = isInDeclaredWorkspace(params, ctx);

        // 3. HITL guard (bypassé si chemin dans workspace déclaré)
        boolean hitlApproved = false;
        if (!bypassHitl && hitlGuard != null) {
            log.debug("Vérification HITL pour l'outil '{}' (risque {})", toolName, tool.riskLevel());
            HitlVerdict verdict = hitlGuard.check(toolName, tool.description(), tool.riskLevel(), params, ctx);
            if (!verdict.allowed()) {
                log.info("Outil '{}' refusé par HITL : {}", toolName, verdict.reason());
                return ToolResult.fail("denied: " + verdict.reason());
            }
            log.debug("Outil '{}' autorisé par HITL : {}", toolName, verdict.reason());
            hitlApproved = true;
        }

        // 4. Path validation — transmet le flag d'approbation HITL pour autoriser les chemins
        //    absolus non-système hors workspace qui ont été approuvés par l'humain
        if (pathValidator != null) {
            try {
                pathValidator.validateParams(params, ctx, bypassHitl || hitlApproved);
            } catch (ToolException e) {
                log.info("Outil '{}' refusé par PathValidator : {}", toolName, e.getMessage());
                return ToolResult.fail("path violation: " + e.getMessage());
            }
        }

        // 5. Exécution avec timeout
        long timeoutMs = tool.maxExecutionTimeMs();
        log.debug("Exécution de l'outil '{}' (timeout {} ms)", toolName, timeoutMs);

        try {
            ToolResult result = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return tool.execute(params, ctx);
                        } catch (ToolException e) {
                            log.info("ToolException lors de l'exécution de '{}' : {}", toolName, e.getMessage());
                            return ToolResult.fail(e.getMessage());
                        }
                    })
                    .get(timeoutMs, TimeUnit.MILLISECONDS);

            log.info("Outil '{}' terminé (succès={})", toolName, result.success());
            log.debug("Résultat de '{}' : {}", toolName, result.data());
            return result;

        } catch (TimeoutException e) {
            log.info("Outil '{}' timeout après {} ms", toolName, timeoutMs);
            return ToolResult.fail("timeout after " + timeoutMs + " ms");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Outil '{}' interrompu", toolName);
            return ToolResult.fail("interrupted");

        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.info("Outil '{}' erreur inattendue : {}", toolName, cause.getMessage());
            return ToolResult.fail("execution error: " + cause.getMessage());
        }
    }

    /**
     * Retourne le premier chemin absolu pointant vers un répertoire système dangereux.
     *
     * <p>Seuls les chemins <em>absolus</em> sont inspectés ici. Les chemins relatifs qui
     * tentent d'échapper au workspace (path traversal) sont détectés plus tard par
     * {@link PathValidator}.</p>
     *
     * @param params paramètres de l'outil
     * @return le chemin dangereux, ou {@link Optional#empty()} si aucun n'est détecté
     */
    private Optional<String> findDangerousAbsolutePath(Map<String, Object> params) {
        if (params == null) return Optional.empty();
        for (Object value : params.values()) {
            if (value instanceof String path && PathValidator.PATH_LIKE_PATTERN.matcher(path).find()) {
                try {
                    Path p = Path.of(path);
                    if (p.isAbsolute() && SystemPathGuard.isDangerous(p.normalize())) {
                        return Optional.of(path);
                    }
                } catch (Exception ignored) { /* chemin invalide → ignoré */ }
            }
        }
        return Optional.empty();
    }

    /**
     * Vérifie si au moins un paramètre chemin de l'appel est dans un dossier externe déclaré
     * pour le projet courant.
     *
     * <p>Retourne {@code false} (pas de bypass) si :</p>
     * <ul>
     *   <li>le {@link WorkspaceRegistry} n'est pas injecté ;</li>
     *   <li>le contexte est {@code null} ou ne porte pas de {@code projectId} ;</li>
     *   <li>aucun paramètre String ne ressemble à un chemin ;</li>
     *   <li>aucun chemin détecté n'appartient à un dossier déclaré du projet.</li>
     * </ul>
     *
     * @param params paramètres de l'outil
     * @param ctx    contexte d'exécution courant
     * @return {@code true} si le HITL write doit être bypassé
     */
    private boolean isInDeclaredWorkspace(Map<String, Object> params, AgentContext ctx) {
        if (workspaceRegistry == null || params == null || ctx == null) {
            return false;
        }
        String projectId = ctx.projectId();
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        for (Object value : params.values()) {
            if (value instanceof String path && PathValidator.PATH_LIKE_PATTERN.matcher(path).find()) {
                if (workspaceRegistry.isInDeclaredWorkspace(path, projectId)) {
                    log.info("Chemin '{}' dans workspace déclaré pour le projet '{}' — HITL write bypassé",
                            path, projectId);
                    return true;
                }
                log.debug("Chemin testé contre workspace déclaré : '{}' (projet '{}') → non déclaré",
                        path, projectId);
            }
        }
        return false;
    }
}
