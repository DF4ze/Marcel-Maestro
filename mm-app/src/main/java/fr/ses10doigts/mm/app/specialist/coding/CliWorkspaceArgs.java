package fr.ses10doigts.mm.app.specialist.coding;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilitaire de dérivation des racines à autoriser pour les CLI de coding.
 *
 * <p>Le répertoire courant (cwd) est déjà accessible au CLI ; seules les <em>autres</em> racines
 * déclarées du projet doivent être ajoutées explicitement (via {@code --add-dir} pour Claude ou
 * {@code writable_roots} pour Codex). Cette classe centralise ce calcul, identique pour les deux
 * agents.</p>
 */
final class CliWorkspaceArgs {

    private CliWorkspaceArgs() {
    }

    /**
     * Retourne les workspaces déclarés distincts du répertoire courant, normalisés.
     *
     * @param context contexte de mission (workspaces déclarés + répertoire courant)
     * @return chemins absolus normalisés à autoriser en plus du cwd ; jamais {@code null}
     */
    static List<String> additionalWorkspaces(MarcelContext context) {
        List<String> declared = context.getDeclaredWorkspaces();
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        Path workingPath = normalizeOrNull(context.getWorkingDirectory());
        List<String> result = new ArrayList<>();
        for (String declaredPath : declared) {
            if (declaredPath == null || declaredPath.isBlank()) {
                continue;
            }
            Path candidate = normalizeOrNull(declaredPath);
            if (candidate == null || candidate.equals(workingPath) || result.contains(candidate.toString())) {
                continue;
            }
            result.add(candidate.toString());
        }
        return result;
    }

    private static Path normalizeOrNull(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
