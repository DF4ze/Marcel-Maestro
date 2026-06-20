package fr.ses10doigts.mm.core.tool;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Valide que les paramètres contenant des chemins de fichiers ne sortent pas
 * d'un workspace autorisé (ADR-004, PB-10).
 *
 * <p>Scan tous les paramètres {@code String} d'un appel d'outil. Si un paramètre
 * ressemble a un chemin (contient un {@code /} ou {@code \}), il est resolu
 * contre le {@link #workspaceRoot} et verifie qu'il ne traverse pas au-dessus.</p>
 *
 * <p>Concu pour etre injecte dans le {@link ToolExecutionGuard}. {@code null}-safe :
 * si aucun workspace n'est configure, le guard n'instancie pas ce validateur.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class PathValidator {

    /** Regex heuristique pour detecter un parametre ressemblant a un chemin. */
    private static final Pattern PATH_LIKE = Pattern.compile("[/\\\\]");

    private final Path workspaceRoot;

    /**
     * Valide tous les parametres string d'un appel d'outil.
     *
     * @param params parametres de l'outil
     * @throws ToolException si un chemin sort du workspace
     */
    public void validateParams(Map<String, Object> params) throws ToolException {
        if (params == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof String value && PATH_LIKE.matcher(value).find()) {
                log.debug("Validation du chemin pour le parametre '{}' : {}", entry.getKey(), value);
                validatePath(value);
            }
        }
    }

    /**
     * Valide qu'un chemin ne sort pas du workspace.
     *
     * @param path chemin a valider
     * @throws ToolException si le chemin est invalide ou sort du workspace
     */
    public void validatePath(String path) throws ToolException {
        try {
            Path resolved = workspaceRoot.resolve(path).normalize();
            if (!resolved.startsWith(workspaceRoot)) {
                log.info("Chemin rejete (traversal) : {} -> {}", path, resolved);
                throw new ToolException(
                        "chemin interdit : '" + path + "' sort du workspace '" + workspaceRoot + "'");
            }
            log.debug("Chemin valide : {} -> {}", path, resolved);
        } catch (InvalidPathException e) {
            log.info("Chemin invalide rejete : {}", path);
            throw new ToolException("chemin invalide : '" + path + "'", e);
        }
    }
}
