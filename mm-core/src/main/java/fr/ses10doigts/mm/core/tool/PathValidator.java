package fr.ses10doigts.mm.core.tool;

import fr.ses10doigts.mm.core.agent.AgentContext;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Valide que les parametres contenant des chemins de fichiers sont dans un espace de travail
 * autorise (ADR-004, PB-10, ADR-023).
 *
 * <h3>Hierarchie de decision (par ordre de priorite)</h3>
 * <ol>
 *   <li><strong>Workspace interne</strong> ({@link #workspaceRoot}) -> autorise sans condition.</li>
 *   <li><strong>Dossier externe declare</strong> via {@link WorkspaceRegistry} -> autorise.</li>
 *   <li><strong>Chemin systeme dangereux</strong> (detecte par {@link SystemPathGuard}) ->
 *       {@link ToolException} inconditionnelle - aucun bypass possible, meme avec
 *       {@code hitlApproved}.</li>
 *   <li><strong>Chemin relatif qui echappe au workspace</strong> (path traversal {@code ../}) ->
 *       {@link ToolException} inconditionnelle.</li>
 *   <li><strong>Chemin absolu non-systeme hors workspace</strong> :
 *       <ul>
 *         <li>Si {@code hitlApproved = true} (HITL a approuve ou bypass workspace declare) ->
 *             autorise.</li>
 *         <li>Sinon -> {@link ToolException}.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Le flag {@code hitlApproved} est positionne par {@link ToolExecutionGuard} apres que
 * l'humain a approuve l'operation via le HITL gate. Cela permet a un agent d'ecrire dans
 * n'importe quel dossier non-systeme si l'utilisateur le permet explicitement, sans avoir
 * a declarer ce dossier comme workspace externe.</p>
 *
 * <p>{@link WorkspaceRegistry} est optionnel : s'il est {@code null} ou si le contexte ne
 * porte pas de {@code projectId}, seul le workspace interne est autorise en mode strict.</p>
 *
 * <p>Concu pour etre injecte dans le {@link ToolExecutionGuard}.</p>
 */
@Slf4j
public class PathValidator {

    /**
     * Regex heuristique pour detecter un parametre ressemblant a un chemin.
     * Package-private pour permettre la reutilisation dans {@link ToolExecutionGuard}.
     */
    static final Pattern PATH_LIKE_PATTERN = Pattern.compile("[/\\\\]");
    static final Set<String> PATH_PARAM_KEYS = Set.of(
            "path", "file", "filepath", "filename", "destination", "source", "target", "dir", "directory");

    private final Path workspaceRoot;
    private final WorkspaceRegistry workspaceRegistry;

    /**
     * Constructeur complet : workspace interne + registre de dossiers externes.
     *
     * @param workspaceRoot racine du workspace interne gere par Marcel
     * @param workspaceRegistry registre des dossiers externes ; peut etre {@code null}
     */
    public PathValidator(Path workspaceRoot, WorkspaceRegistry workspaceRegistry) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.workspaceRegistry = workspaceRegistry;
    }

    /**
     * Constructeur sans registre externe (workspace interne uniquement).
     *
     * <p>Utilise dans les tests unitaires de mm-core qui n'ont pas acces a la DB.</p>
     *
     * @param workspaceRoot racine du workspace interne gere par Marcel
     */
    public PathValidator(Path workspaceRoot) {
        this(workspaceRoot, null);
    }

    /**
     * Valide tous les parametres string d'un appel d'outil (mode strict).
     *
     * <p>Equivalent a {@link #validateParams(Map, AgentContext, boolean)} avec
     * {@code hitlApproved = false} - aucun chemin hors workspace autorise.</p>
     *
     * @param params parametres de l'outil ; {@code null} est tolere
     * @param ctx contexte d'execution courant ; {@code null} -> seul le workspace interne
     *            est autorise
     * @throws ToolException si un parametre ressemblant a un chemin est hors de tout workspace
     *         autorise, est un chemin systeme ou est un path traversal
     */
    public void validateParams(Map<String, Object> params, AgentContext ctx) throws ToolException {
        validateParams(params, ctx, false);
    }

    /**
     * Valide tous les parametres string d'un appel d'outil.
     *
     * <p>Quand {@code hitlApproved} est {@code true} (l'humain a approuve via HITL ou le
     * chemin est dans un workspace declare), les chemins absolus non-systeme hors workspace
     * sont acceptes. Les chemins systeme et les path traversals relatifs sont toujours rejetes,
     * quel que soit le flag.</p>
     *
     * @param params parametres de l'outil ; {@code null} est tolere
     * @param ctx contexte d'execution courant
     * @param hitlApproved {@code true} si le HITL gate a accorde son consentement
     * @throws ToolException si un parametre chemin echoue a la validation
     */
    public void validateParams(Map<String, Object> params, AgentContext ctx, boolean hitlApproved)
            throws ToolException {
        if (params == null) {
            return;
        }
        String projectId = (ctx != null) ? ctx.projectId() : null;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || !PATH_PARAM_KEYS.contains(key.toLowerCase())) {
                continue;
            }
            if (entry.getValue() instanceof String value && PATH_LIKE_PATTERN.matcher(value).find()) {
                log.debug("Validation du chemin pour le parametre '{}' : {}", key, value);
                validatePath(value, projectId, hitlApproved);
            }
        }
    }

    /**
     * Valide qu'un chemin est dans un workspace autorise (mode strict).
     *
     * <p>Equivalent a {@link #validatePath(String, String, boolean)} avec
     * {@code hitlApproved = false}.</p>
     *
     * @param path chemin a valider (relatif ou absolu)
     * @param projectId identifiant du projet ; {@code null} -> dossiers externes ignores
     * @throws ToolException si le chemin est invalide, systeme, traversal ou hors workspace
     */
    public void validatePath(String path, String projectId) throws ToolException {
        validatePath(path, projectId, false);
    }

    /**
     * Valide qu'un chemin respecte la politique d'acces fichier.
     *
     * <p>Voir la JavaDoc de la classe pour la hierarchie de decision complete.</p>
     *
     * @param path chemin a valider (relatif ou absolu)
     * @param projectId identifiant du projet ; {@code null} -> dossiers externes ignores
     * @param hitlApproved {@code true} si le HITL gate a accorde son consentement
     * @throws ToolException si le chemin est invalide, systeme, traversal ou hors workspace
     *         sans approbation HITL
     */
    public void validatePath(String path, String projectId, boolean hitlApproved) throws ToolException {
        try {
            Path p = Path.of(path);
            Path resolved = workspaceRoot.resolve(p).normalize();

            if (resolved.startsWith(workspaceRoot)) {
                log.debug("Chemin autorise (workspace interne) : {} -> {}", path, resolved);
                return;
            }

            if (workspaceRegistry != null && projectId != null && !projectId.isBlank()) {
                String absolutePath = resolved.toAbsolutePath().toString();
                if (workspaceRegistry.isInDeclaredWorkspace(absolutePath, projectId)) {
                    log.debug("Chemin autorise (workspace externe declare) : {} -> {}", path, resolved);
                    return;
                }
            }

            // Resolution multi-dossier : un chemin RELATIF est tente sous chaque racine
            // declaree du projet. S'il tombe sous l'une d'elles (sans en sortir), il est
            // autorise. Aligne l'autorisation du validateur sur la resolution effective des
            // outils (read_file/write_file/read_logs) qui cherchent dans tous les workspaces.
            if (!p.isAbsolute() && workspaceRegistry != null && projectId != null && !projectId.isBlank()) {
                for (String root : workspaceRegistry.declaredRoots(projectId)) {
                    if (root == null || root.isBlank()) {
                        continue;
                    }
                    Path declaredRoot = Path.of(root).toAbsolutePath().normalize();
                    Path candidate = declaredRoot.resolve(p).normalize();
                    if (candidate.startsWith(declaredRoot)) {
                        log.debug("Chemin relatif autorise (workspace declare) : {} -> {}", path, candidate);
                        return;
                    }
                }
            }

            Path resolvedAbs = resolved.toAbsolutePath();

            if (SystemPathGuard.isDangerous(resolvedAbs)) {
                log.info("Chemin systeme dangereux rejete : {} -> {}", path, resolvedAbs);
                throw new ToolException("chemin systeme interdit : '" + path + "'");
            }

            if (!p.isAbsolute()) {
                log.info("Path traversal detecte : {} -> {}", path, resolvedAbs);
                throw new ToolException("path traversal detecte : '" + path + "' sort du workspace autorise");
            }

            if (hitlApproved) {
                log.debug("Chemin absolu hors-workspace autorise (HITL approuve) : {} -> {}", path, resolvedAbs);
                return;
            }

            log.info("Chemin absolu hors-workspace rejete (HITL non approuve) : {} -> {}", path, resolvedAbs);
            throw new ToolException("chemin interdit : '" + path + "' est hors de tout workspace autorise");
        } catch (InvalidPathException e) {
            log.info("Chemin invalide rejete : {}", path);
            throw new ToolException("chemin invalide : '" + path + "'", e);
        }
    }
}
