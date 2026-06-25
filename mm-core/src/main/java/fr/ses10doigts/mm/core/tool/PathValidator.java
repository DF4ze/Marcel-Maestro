package fr.ses10doigts.mm.core.tool;

import fr.ses10doigts.mm.core.agent.AgentContext;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Valide que les paramètres contenant des chemins de fichiers sont dans un espace de travail
 * autorisé (ADR-004, PB-10, ADR-023).
 *
 * <h3>Hiérarchie de décision (par ordre de priorité)</h3>
 * <ol>
 *   <li><strong>Workspace interne</strong> ({@link #workspaceRoot}) → autorisé sans condition.</li>
 *   <li><strong>Dossier externe déclaré</strong> via {@link WorkspaceRegistry} → autorisé.</li>
 *   <li><strong>Chemin système dangereux</strong> (détecté par {@link SystemPathGuard}) →
 *       {@link ToolException} inconditionnelle — aucun bypass possible, même avec
 *       {@code hitlApproved}.</li>
 *   <li><strong>Chemin relatif qui échappe au workspace</strong> (path traversal {@code ../}) →
 *       {@link ToolException} inconditionnelle.</li>
 *   <li><strong>Chemin absolu non-système hors workspace</strong> :
 *       <ul>
 *         <li>Si {@code hitlApproved = true} (HITL a approuvé ou bypass workspace déclaré) →
 *             autorisé.</li>
 *         <li>Sinon → {@link ToolException}.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Le flag {@code hitlApproved} est positionné par {@link ToolExecutionGuard} après que
 * l'humain a approuvé l'opération via le HITL gate. Cela permet à un agent d'écrire dans
 * n'importe quel dossier non-système si l'utilisateur le permet explicitement, sans avoir
 * à déclarer ce dossier comme workspace externe.</p>
 *
 * <p>{@link WorkspaceRegistry} est optionnel : s'il est {@code null} ou si le contexte ne
 * porte pas de {@code projectId}, seul le workspace interne est autorisé en mode strict.</p>
 *
 * <p>Conçu pour être injecté dans le {@link ToolExecutionGuard}.</p>
 */
@Slf4j
public class PathValidator {

    /**
     * Regex heuristique pour détecter un paramètre ressemblant à un chemin.
     * Package-private pour permettre la réutilisation dans {@link ToolExecutionGuard}.
     */
    static final Pattern PATH_LIKE_PATTERN = Pattern.compile("[/\\\\]");

    private final Path workspaceRoot;
    private final WorkspaceRegistry workspaceRegistry;

    /**
     * Constructeur complet : workspace interne + registre de dossiers externes.
     *
     * @param workspaceRoot     racine du workspace interne géré par Marcel
     * @param workspaceRegistry registre des dossiers externes ; peut être {@code null}
     */
    public PathValidator(Path workspaceRoot, WorkspaceRegistry workspaceRegistry) {
        this.workspaceRoot = workspaceRoot;
        this.workspaceRegistry = workspaceRegistry;
    }

    /**
     * Constructeur sans registre externe (workspace interne uniquement).
     *
     * <p>Utilisé dans les tests unitaires de mm-core qui n'ont pas accès à la DB.</p>
     *
     * @param workspaceRoot racine du workspace interne géré par Marcel
     */
    public PathValidator(Path workspaceRoot) {
        this(workspaceRoot, null);
    }

    /**
     * Valide tous les paramètres string d'un appel d'outil (mode strict).
     *
     * <p>Équivalent à {@link #validateParams(Map, AgentContext, boolean)} avec
     * {@code hitlApproved = false} — aucun chemin hors workspace autorisé.</p>
     *
     * @param params paramètres de l'outil ; {@code null} est toléré
     * @param ctx    contexte d'exécution courant ; {@code null} → seul le workspace interne
     *               est autorisé
     * @throws ToolException si un paramètre ressemblant à un chemin est hors de tout workspace
     *                       autorisé, est un chemin système ou est un path traversal
     */
    public void validateParams(Map<String, Object> params, AgentContext ctx) throws ToolException {
        validateParams(params, ctx, false);
    }

    /**
     * Valide tous les paramètres string d'un appel d'outil.
     *
     * <p>Quand {@code hitlApproved} est {@code true} (l'humain a approuvé via HITL ou le
     * chemin est dans un workspace déclaré), les chemins absolus non-système hors workspace
     * sont acceptés. Les chemins système et les path traversals relatifs sont toujours rejetés,
     * quel que soit le flag.</p>
     *
     * @param params       paramètres de l'outil ; {@code null} est toléré
     * @param ctx          contexte d'exécution courant
     * @param hitlApproved {@code true} si le HITL gate a accordé son consentement
     * @throws ToolException si un paramètre chemin échoue à la validation
     */
    public void validateParams(Map<String, Object> params, AgentContext ctx,
                               boolean hitlApproved) throws ToolException {
        if (params == null) {
            return;
        }
        String projectId = (ctx != null) ? ctx.projectId() : null;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof String value && PATH_LIKE_PATTERN.matcher(value).find()) {
                log.debug("Validation du chemin pour le paramètre '{}' : {}", entry.getKey(), value);
                validatePath(value, projectId, hitlApproved);
            }
        }
    }

    /**
     * Valide qu'un chemin est dans un workspace autorisé (mode strict).
     *
     * <p>Équivalent à {@link #validatePath(String, String, boolean)} avec
     * {@code hitlApproved = false}.</p>
     *
     * @param path      chemin à valider (relatif ou absolu)
     * @param projectId identifiant du projet ; {@code null} → dossiers externes ignorés
     * @throws ToolException si le chemin est invalide, système, traversal ou hors workspace
     */
    public void validatePath(String path, String projectId) throws ToolException {
        validatePath(path, projectId, false);
    }

    /**
     * Valide qu'un chemin respecte la politique d'accès fichier.
     *
     * <p>Voir la JavaDoc de la classe pour la hiérarchie de décision complète.</p>
     *
     * @param path         chemin à valider (relatif ou absolu)
     * @param projectId    identifiant du projet ; {@code null} → dossiers externes ignorés
     * @param hitlApproved {@code true} si le HITL gate a accordé son consentement
     * @throws ToolException si le chemin est invalide, système, traversal ou hors workspace
     *                       sans approbation HITL
     */
    public void validatePath(String path, String projectId, boolean hitlApproved) throws ToolException {
        try {
            Path p = Path.of(path);
            Path resolved = workspaceRoot.resolve(p).normalize();

            // 1. Workspace interne
            if (resolved.startsWith(workspaceRoot)) {
                log.debug("Chemin autorisé (workspace interne) : {} -> {}", path, resolved);
                return;
            }

            // 2. Dossier externe déclaré (si registre + projectId disponibles)
            if (workspaceRegistry != null && projectId != null && !projectId.isBlank()) {
                String absolutePath = resolved.toAbsolutePath().toString();
                if (workspaceRegistry.isInDeclaredWorkspace(absolutePath, projectId)) {
                    log.debug("Chemin autorisé (workspace externe déclaré) : {} -> {}", path, resolved);
                    return;
                }
            }

            Path resolvedAbs = resolved.toAbsolutePath();

            // 3. Chemin système dangereux → rejet inconditionnel (défense en profondeur)
            if (SystemPathGuard.isDangerous(resolvedAbs)) {
                log.info("Chemin système dangereux rejeté : {} -> {}", path, resolvedAbs);
                throw new ToolException("chemin système interdit : '" + path + "'");
            }

            // 4. Chemin RELATIF qui échappe au workspace → path traversal
            if (!p.isAbsolute()) {
                log.info("Path traversal détecté : {} -> {}", path, resolvedAbs);
                throw new ToolException(
                        "path traversal détecté : '" + path + "' sort du workspace autorisé");
            }

            // 5. Chemin absolu non-système hors workspace
            if (hitlApproved) {
                log.debug("Chemin absolu hors-workspace autorisé (HITL approuvé) : {} -> {}",
                        path, resolvedAbs);
                return;
            }

            log.info("Chemin absolu hors-workspace rejeté (HITL non approuvé) : {} -> {}",
                    path, resolvedAbs);
            throw new ToolException(
                    "chemin interdit : '" + path + "' est hors de tout workspace autorisé");

        } catch (InvalidPathException e) {
            log.info("Chemin invalide rejeté : {}", path);
            throw new ToolException("chemin invalide : '" + path + "'", e);
        }
    }
}
