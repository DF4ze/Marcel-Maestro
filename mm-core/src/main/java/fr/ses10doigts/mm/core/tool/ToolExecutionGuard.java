package fr.ses10doigts.mm.core.tool;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.hitl.HitlGuard;
import fr.ses10doigts.mm.core.hitl.HitlVerdict;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * Couche de securite transverse enveloppant chaque execution d'outil (etape 6).
 *
 * <p>Applique sequentiellement :</p>
 * <ol>
 *   <li><strong>HITL</strong> : si un {@link HitlGuard} est present, verifie le
 *       consentement humain. Un refus retourne un {@link ToolResult#fail} sans
 *       executer l'outil.</li>
 *   <li><strong>Path validation</strong> : si un {@link PathValidator} est present,
 *       valide que les parametres de type chemin ne sortent pas du workspace.</li>
 *   <li><strong>Timeout</strong> : execute l'outil dans un {@link CompletableFuture}
 *       borne par {@link AgentTool#maxExecutionTimeMs()}.</li>
 * </ol>
 *
 * <p>Concu pour etre instancie une fois par le moteur et partage entre tous les outils.
 * Les deux gardes (HITL, path) sont optionnels ({@code null}-safe).</p>
 */
@Slf4j
public class ToolExecutionGuard {

    private final HitlGuard hitlGuard;
    private final PathValidator pathValidator;

    /**
     * @param hitlGuard     garde HITL ; peut etre {@code null} (pas de consentement requis)
     * @param pathValidator validateur de chemins ; peut etre {@code null} (pas de restriction)
     */
    public ToolExecutionGuard(HitlGuard hitlGuard, PathValidator pathValidator) {
        this.hitlGuard = hitlGuard;
        this.pathValidator = pathValidator;
    }

    /**
     * Execute un outil avec les gardes de securite.
     *
     * @param tool   outil a executer
     * @param params parametres deserialises
     * @param ctx    contexte d'execution
     * @return resultat de l'execution (succes ou echec)
     */
    public ToolResult execute(AgentTool tool, Map<String, Object> params, AgentContext ctx) {
        String toolName = tool.name();

        // 1. HITL guard
        if (hitlGuard != null) {
            log.info("Verification HITL pour l'outil '{}' (risque {})", toolName, tool.riskLevel());
            HitlVerdict verdict = hitlGuard.check(toolName, tool.riskLevel(), ctx);
            if (!verdict.allowed()) {
                log.info("Outil '{}' refuse par HITL : {}", toolName, verdict.reason());
                return ToolResult.fail("denied: " + verdict.reason());
            }
            log.debug("Outil '{}' autorise par HITL : {}", toolName, verdict.reason());
        }

        // 2. Path validation
        if (pathValidator != null) {
            try {
                pathValidator.validateParams(params);
            } catch (ToolException e) {
                log.info("Outil '{}' refuse par PathValidator : {}", toolName, e.getMessage());
                return ToolResult.fail("path violation: " + e.getMessage());
            }
        }

        // 3. Execution avec timeout
        long timeoutMs = tool.maxExecutionTimeMs();
        log.info("Execution de l'outil '{}' (timeout {} ms)", toolName, timeoutMs);

        try {
            ToolResult result = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return tool.execute(params, ctx);
                        } catch (ToolException e) {
                            log.info("ToolException lors de l'execution de '{}' : {}", toolName, e.getMessage());
                            return ToolResult.fail(e.getMessage());
                        }
                    })
                    .get(timeoutMs, TimeUnit.MILLISECONDS);

            log.info("Outil '{}' termine (succes={})", toolName, result.success());
            log.debug("Resultat de '{}' : {}", toolName, result.data());
            return result;

        } catch (TimeoutException e) {
            log.info("Outil '{}' timeout apres {} ms", toolName, timeoutMs);
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
}
