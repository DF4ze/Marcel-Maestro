package fr.ses10doigts.mm.starter.project;

import fr.ses10doigts.mm.core.tool.WorkspaceRegistry;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implémentation JPA du port {@link WorkspaceRegistry} (ADR-023, E2-M3).
 *
 * <p>Interroge {@link ProjectWorkspaceRepository} pour récupérer les dossiers externes
 * déclarés pour un projet, puis vérifie si le chemin testé est un sous-chemin de l'un
 * d'eux.</p>
 *
 * <p><strong>Normalisation des chemins</strong> : {@code Path.of(path).toAbsolutePath().normalize()}
 * est appliqué sur chaque chemin (testé et déclaré). La comparaison utilise
 * {@code Path.startsWith()}, qui est OS-aware :</p>
 * <ul>
 *   <li>Windows : case-insensitive (C:\Foo == c:\foo).</li>
 *   <li>Linux/macOS : case-sensitive.</li>
 * </ul>
 * <p>Pas de {@code toRealPath()} : le fichier peut ne pas encore exister (écriture)
 * et cette méthode suit les symlinks, ce qui modifierait le chemin réel.</p>
 *
 * <p>Autoconfigurée par {@code MmCoreAutoConfiguration} avec {@code @ConditionalOnMissingBean}.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class JpaWorkspaceRegistry implements WorkspaceRegistry {

    private final ProjectWorkspaceRepository workspaceRepository;

    /**
     * Retourne {@code true} si {@code absolutePath} est un sous-chemin d'un dossier
     * externe déclaré pour le projet {@code projectId}.
     *
     * <p>Retourne {@code false} immédiatement si {@code projectId} est {@code null} ou
     * vide, ou si le projet n'a aucun dossier externe déclaré.</p>
     *
     * @param absolutePath chemin absolu à tester (peut contenir {@code ..} ou backslash)
     * @param projectId    identifiant du projet ; {@code null} → {@code false}
     * @return {@code true} si le chemin normalisé est sous un dossier déclaré du projet
     */
    @Override
    public boolean isInDeclaredWorkspace(String absolutePath, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            log.debug("isInDeclaredWorkspace : projectId absent → false");
            return false;
        }

        List<ProjectWorkspaceEntity> declared = workspaceRepository.findAllByProjectId(projectId);
        if (declared.isEmpty()) {
            log.debug("isInDeclaredWorkspace : aucun workspace externe pour le projet '{}' → false",
                    projectId);
            return false;
        }

        Path normalizedInput = Path.of(absolutePath).toAbsolutePath().normalize();

        for (ProjectWorkspaceEntity ws : declared) {
            Path normalizedDeclared = Path.of(ws.getPath()).toAbsolutePath().normalize();
            if (normalizedInput.startsWith(normalizedDeclared)) {
                log.debug("isInDeclaredWorkspace : '{}' est sous '{}' → true",
                        normalizedInput, normalizedDeclared);
                return true;
            }
        }

        log.debug("isInDeclaredWorkspace : '{}' n'est sous aucun workspace du projet '{}' → false",
                normalizedInput, projectId);
        return false;
    }

    /**
     * Retourne les chemins absolus normalisés des dossiers externes déclarés du projet.
     *
     * <p>Utilisé par {@link fr.ses10doigts.mm.core.tool.PathValidator} pour résoudre un chemin
     * relatif sous chacune des racines déclarées (résolution multi-dossier). Le workspace
     * interne du projet n'est pas inclus ici : il est déjà couvert par la racine interne du
     * validateur.</p>
     *
     * @param projectId identifiant du projet ; {@code null}/vide → liste vide
     * @return chemins absolus normalisés des dossiers déclarés ; jamais {@code null}
     */
    @Override
    public List<String> declaredRoots(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        return workspaceRepository.findAllByProjectId(projectId).stream()
                .map(ProjectWorkspaceEntity::getPath)
                .filter(path -> path != null && !path.isBlank())
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .toList();
    }
}
