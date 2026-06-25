package fr.ses10doigts.mm.starter.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository Spring Data pour les {@link ProjectWorkspaceEntity}.
 *
 * <p>Fournit le chargement des dossiers externes d'un projet et
 * leur suppression par projet (utilisé par {@code ProjectService.delete()}).</p>
 */
public interface ProjectWorkspaceRepository extends JpaRepository<ProjectWorkspaceEntity, String> {

    /**
     * Retourne tous les dossiers externes déclarés pour un projet donné.
     *
     * @param projectId l'ID du projet
     * @return liste des workspaces, éventuellement vide
     */
    List<ProjectWorkspaceEntity> findAllByProjectId(String projectId);

    /**
     * Supprime tous les dossiers externes d'un projet donné.
     *
     * <p>Appelé avant la suppression du projet lui-même, en complément du
     * {@code ON DELETE CASCADE} SQL (garantit la cohérence même si le cascade
     * n'est pas déclenché par le contexte JPA).</p>
     *
     * @param projectId l'ID du projet
     */
    @Transactional
    void deleteAllByProjectId(String projectId);
}
